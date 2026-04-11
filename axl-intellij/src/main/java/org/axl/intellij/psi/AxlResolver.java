package org.axl.intellij.psi;

import com.intellij.openapi.util.RecursionManager;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.intellij.java.AxlLightClasses;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.parser.AxlElementTypes;
import org.jetbrains.annotations.Nullable;

/** Name lookup in .axl files: scopes, top-level declarations, imports and Java classes. */
public final class AxlResolver {

    private static final Map<String, String> BUILTIN_TYPES = Map.of(
            "Int", "java.lang.Integer", "Long", "java.lang.Long", "Boolean", "java.lang.Boolean", "Double", "java.lang.Double");
    private static final Map<String, PsiPrimitiveType> PRIMITIVES = Map.of("Int", PsiTypes.intType(),
            "Long", PsiTypes.longType(), "Boolean", PsiTypes.booleanType(), "Double", PsiTypes.doubleType());

    private AxlResolver() {}

    /** What a plain name refers to; several overloads when the call's arguments do not decide. */
    static List<PsiElement> resolveName(PsiElement place, String name) {
        for (PsiElement local : localDeclarations(place)) {
            if (local instanceof AxlNamedElement named && name.equals(named.getName())) {
                return List.of(named);
            }
        }
        if (name.equals("this")) {
            AxlTypeDeclaration owner = PsiTreeUtil.getParentOfType(place, AxlTypeDeclaration.class);
            return owner == null ? List.of() : List.of(owner);
        }
        if (name.equals("it")) {
            PsiElement lambda = implicitParameterOwner(place);
            if (lambda != null) {
                return List.of(lambda);
            }
        }
        AxlFile file = file(place);
        if (file == null) {
            return List.of();
        }
        List<AxlFunction> functions = file.functions().stream().filter(f -> name.equals(f.getName())).toList();
        if (!functions.isEmpty()) {
            return overloadsFor(place, functions);
        }
        for (AxlVariable value : file.values()) {
            if (name.equals(value.getName())) {
                return List.of(value);
            }
        }
        for (AxlTypeDeclaration declaration : file.typeDeclarations()) {
            if (name.equals(declaration.getName())) {
                return List.of(declaration);
            }
        }
        boolean inImport = PsiTreeUtil.getParentOfType(place, AxlImport.class) != null;
        for (AxlImport imp : file.imports()) {
            if (inImport || !imp.simpleName().equals(name)) {
                continue;
            }
            String qualified = imp.qualifiedName();
            if (imp.kind() == AxlImport.Kind.TYPE) {
                PsiClass cls = findClass(place, qualified);
                return cls == null ? List.of() : List.of(cls);
            }
            PsiClass owner = findClass(place, qualified.substring(0, Math.max(0, qualified.lastIndexOf('.'))));
            return owner == null ? List.of() : overloadsFor(place, members(owner, name));
        }
        String pkg = file.packageName();
        String builtin = BUILTIN_TYPES.get(name);
        for (String candidate : List.of(builtin != null ? builtin : pkg.isEmpty() ? name : pkg + "." + name,
                "java.lang." + name, "axl.lang." + name)) {
            PsiClass cls = findClass(place, candidate);
            if (cls != null) {
                return List.of(cls);
            }
        }
        PsiPackage found = JavaPsiFacade.getInstance(place.getProject()).findPackage(name);
        return found == null ? List.of() : List.of(found);
    }

    /** The innermost lambda without a parameter list, whose single parameter is `it`. */
    private static @Nullable PsiElement implicitParameterOwner(PsiElement place) {
        for (PsiElement parent = place.getParent(); parent != null && !(parent instanceof AxlFile); parent = parent.getParent()) {
            if (parent.getNode().getElementType() == AxlElementTypes.LAMBDA
                    && parent.getNode().findChildByType(AxlTokenTypes.of(AxolotlLexer.ARROW)) == null) {
                return parent;
            }
        }
        return null;
    }

    static @Nullable PsiElement inPackage(PsiElement place, PsiPackage pkg, String name) {
        String qualified = pkg.getQualifiedName().isEmpty() ? name : pkg.getQualifiedName() + "." + name;
        PsiClass cls = findClass(place, qualified);
        return cls != null ? cls : JavaPsiFacade.getInstance(place.getProject()).findPackage(qualified);
    }

    /** Methods and fields of a class by name; members of Axolotl files resolve to their declarations. */
    static List<PsiElement> members(PsiClass cls, String name) {
        LinkedHashSet<PsiElement> result = new LinkedHashSet<>();
        for (PsiMethod method : withoutOverridden(cls.findMethodsByName(name, true))) {
            result.add(source(method));
        }
        PsiField field = cls.findFieldByName(name, true);
        if (field != null) {
            result.add(source(field));
        }
        return new ArrayList<>(result);
    }

    /** A method and the ones it overrides are one method: `ArrayList.add` and `List.add`. */
    private static List<PsiMethod> withoutOverridden(PsiMethod[] methods) {
        Set<PsiMethod> overridden = new HashSet<>();
        for (PsiMethod method : methods) {
            overridden.addAll(Arrays.asList(method.findSuperMethods()));
        }
        return Arrays.stream(methods).filter(m -> !overridden.contains(m)).toList();
    }

    /** The class Java sees for an Axolotl type declaration or a Java class; null for other elements. */
    static @Nullable PsiClass asClass(@Nullable PsiElement element) {
        if (element instanceof AxlTypeDeclaration declaration) {
            return AxlLightClasses.classOf(declaration);
        }
        return element instanceof PsiClass cls ? cls : null;
    }

    /**
     * javac's phases of overload resolution: without boxing or varargs first, then with boxing, then
     * with varargs. The first phase with an applicable method decides.
     */
    private enum Phase { STRICT, LOOSE, VARARGS }

    /**
     * Of several functions or methods with a name, the one a call's arguments fit best, as javac
     * picks it; all that fit when that cannot be told. For a name that is not called, its fields.
     * Arguments whose type takes inference fit any parameter.
     */
    static List<PsiElement> overloadsFor(PsiElement place, List<? extends PsiElement> candidates) {
        List<PsiElement> arguments = callArguments(place);
        if (arguments == null) {
            List<PsiElement> fields = candidates.stream().filter(c -> parameterTypes(c) == null).map(c -> (PsiElement) c).toList();
            return new ArrayList<>(fields.isEmpty() ? candidates : fields);
        }
        if (candidates.size() < 2) {
            return new ArrayList<>(candidates);
        }
        List<PsiType> types = arguments.stream().map(AxlResolver::argumentType).toList();
        for (Phase phase : Phase.values()) {
            List<PsiElement> applicable = candidates.stream().filter(c -> isApplicable(c, types, phase))
                    .map(c -> (PsiElement) c).toList();
            if (!applicable.isEmpty()) {
                List<PsiElement> best = applicable.stream()
                        .filter(a -> applicable.stream().allMatch(b -> a == b || isMoreSpecific(a, b))).toList();
                return new ArrayList<>(best.size() == 1 ? best : applicable);
            }
        }
        return new ArrayList<>(candidates);
    }

    /** The arguments when the place is the callee of a call, otherwise null. */
    private static @Nullable List<PsiElement> callArguments(PsiElement place) {
        PsiElement call = place.getParent();
        if (call == null || call.getNode().getElementType() != AxlElementTypes.CALL || call.getFirstChild() != place) {
            return null;
        }
        PsiElement arguments = call.getLastChild();
        if (arguments == null || arguments.getNode().getElementType() != AxlElementTypes.ARGUMENTS) {
            return null;
        }
        return new ArrayList<>(PsiTreeUtil.getChildrenOfTypeAsList(arguments, AxlPsiElement.class));
    }

    /** The parameter types of a function or method, null for one that does not resolve; null for other elements. */
    private static @Nullable List<PsiType> parameterTypes(PsiElement callable) {
        if (callable instanceof AxlFunction function) {
            return function.parameters().stream().map(p -> p.type() == null ? null : javaType(p.type())).toList();
        }
        if (callable instanceof PsiMethod method) {
            return Arrays.stream(method.getParameterList().getParameters()).map(PsiParameter::getType).toList();
        }
        return null;
    }

    private static boolean isVarArgs(PsiElement callable) {
        return callable instanceof PsiMethod method && method.isVarArgs();
    }

    private static boolean isApplicable(PsiElement callable, List<PsiType> arguments, Phase phase) {
        List<PsiType> parameters = parameterTypes(callable);
        if (parameters == null) {
            return false;
        }
        if (phase != Phase.VARARGS) {
            if (arguments.size() != parameters.size()) {
                return false;
            }
            for (int i = 0; i < parameters.size(); i++) {
                if (!accepts(parameters.get(i), arguments.get(i), phase == Phase.STRICT)) {
                    return false;
                }
            }
            return true;
        }
        int fixed = parameters.size() - 1;
        if (!isVarArgs(callable) || arguments.size() < fixed) {
            return false;
        }
        for (int i = 0; i < arguments.size(); i++) {
            PsiType parameter = i < fixed ? parameters.get(i) : ((PsiArrayType) parameters.getLast()).getComponentType();
            if (!accepts(parameter, arguments.get(i), false)) {
                return false;
            }
        }
        return true;
    }

    /** Whether every parameter of a is a subtype of the same parameter of b, as javac's most specific method. */
    private static boolean isMoreSpecific(PsiElement a, PsiElement b) {
        List<PsiType> mine = parameterTypes(a);
        List<PsiType> theirs = parameterTypes(b);
        if (mine == null || theirs == null || mine.size() != theirs.size()) {
            return false;
        }
        for (int i = 0; i < mine.size(); i++) {
            if (!accepts(theirs.get(i), mine.get(i), true)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Unknown types fit anything; generic types are compared without their type arguments. strict:
     * without boxing or unboxing.
     */
    private static boolean accepts(@Nullable PsiType parameter, @Nullable PsiType argument, boolean strict) {
        if (parameter == null || argument == null) {
            return true;
        }
        if (strict && isPrimitive(parameter) != isPrimitive(argument)) {
            return false;
        }
        return TypeConversionUtil.isAssignable(TypeConversionUtil.erasure(parameter), TypeConversionUtil.erasure(argument));
    }

    /** The null type counts as a primitive type in IntelliJ, but takes no boxing. */
    private static boolean isPrimitive(PsiType type) {
        return type instanceof PsiPrimitiveType && type != PsiTypes.nullType();
    }

    /**
     * The type of an argument when it can be told without inference: a literal, a variable with a
     * written type, a field, a constructor call or a call with a written or declared result type.
     */
    private static @Nullable PsiType argumentType(PsiElement argument) {
        IElementType kind = argument.getNode().getElementType();
        if (kind == AxlElementTypes.LITERAL) {
            return literalType(argument);
        }
        if (kind == AxlElementTypes.PARENTHESIZED) {
            PsiElement inner = argument.getFirstChild().getNextSibling();
            return inner == null ? null : argumentType(inner);
        }
        boolean call = kind == AxlElementTypes.CALL;
        PsiElement name = call ? argument.getFirstChild() : argument;
        PsiElement target = name instanceof AxlReferenceExpression ref && ref.getReference() != null ? ref.getReference().resolve() : null;
        return switch (target) {
            case AxlVariable variable when !call -> variable.type() == null ? null : javaType(variable.type());
            case PsiField field when !call -> declaredType(field.getType());
            case AxlFunction function when call -> function.returnType() == null ? null : javaType(function.returnType());
            case PsiMethod method when call -> declaredType(method.getReturnType());
            case PsiClass cls when call -> JavaPsiFacade.getElementFactory(argument.getProject()).createType(cls);
            case AxlTypeDeclaration declaration when call && asClass(declaration) != null ->
                    JavaPsiFacade.getElementFactory(argument.getProject()).createType(asClass(declaration));
            case null, default -> null;
        };
    }

    private static @Nullable PsiType literalType(PsiElement literal) {
        IElementType token = literal.getFirstChild().getNode().getElementType();
        if (token == AxlTokenTypes.STRING) {
            return PsiType.getJavaLangString(literal.getManager(), literal.getResolveScope());
        }
        if (token == AxlTokenTypes.INT) {
            return literal.getText().toLowerCase(Locale.ROOT).endsWith("l") ? PsiTypes.longType() : PsiTypes.intType();
        }
        if (token == AxlTokenTypes.FLOAT) {
            return PsiTypes.doubleType();
        }
        if (token == AxlTokenTypes.of(AxolotlLexer.TRUE) || token == AxlTokenTypes.of(AxolotlLexer.FALSE)) {
            return PsiTypes.booleanType();
        }
        return token == AxlTokenTypes.of(AxolotlLexer.NULL) ? PsiTypes.nullType() : null;
    }

    /** A declared Java type; null for a type variable, whose type takes inference. */
    private static @Nullable PsiType declaredType(@Nullable PsiType type) {
        return type == null || PsiUtil.resolveClassInType(type) instanceof PsiTypeParameter ? null : type;
    }

    /** The Java type of a written type, without its type arguments; null when it does not resolve. */
    public static @Nullable PsiType javaType(AxlType type) {
        boolean nullable = type.getText().endsWith("?");
        PsiPrimitiveType primitive = PRIMITIVES.get(type.name());
        if (primitive != null && !nullable) {
            return primitive;
        }
        PsiClass cls = classOf(type);
        return cls == null ? null : JavaPsiFacade.getElementFactory(type.getProject()).createType(cls);
    }

    private static PsiElement source(PsiMember member) {
        PsiElement navigation = member.getNavigationElement();
        return navigation instanceof AxlNamedElement ? navigation : member;
    }

    /** The class of an expression's value, when it can be told without type inference. */
    static @Nullable PsiClass typeOf(PsiElement expression) {
        if (expression.getNode().getElementType() == AxlElementTypes.PARENTHESIZED) {
            PsiElement inner = expression.getFirstChild().getNextSibling();
            return inner == null ? null : typeOf(inner);
        }
        if (expression.getNode().getElementType() == AxlElementTypes.NOT_NULL) {
            return typeOf(expression.getFirstChild());
        }
        if (expression.getNode().getElementType() == AxlElementTypes.LITERAL && expression.getText().startsWith("\"")) {
            return findClass(expression, "java.lang.String");
        }
        PsiElement target = null;
        if (expression instanceof AxlReferenceExpression ref && ref.getReference() != null) {
            target = ref.getReference().resolve();
        } else if (expression.getNode().getElementType() == AxlElementTypes.CALL
                && expression.getFirstChild() instanceof AxlReferenceExpression callee && callee.getReference() != null) {
            target = callee.getReference().resolve();
            PsiClass constructed = asClass(target);
            if (constructed != null) {
                return constructed;
            }
        }
        return switch (target) {
            case AxlVariable variable -> variableType(expression, variable);
            case AxlFunction function -> function.returnType() == null ? null : classOf(function.returnType());
            case PsiField field -> classOf(field.getType());
            case PsiMethod method -> classOf(method.getReturnType());
            case null, default -> null;
        };
    }

    /** An enum constant has its enum's type; a narrowed value the type it was tested for; others the written or inferred one. */
    private static @Nullable PsiClass variableType(PsiElement place, AxlVariable variable) {
        if (variable.getParent() instanceof AxlTypeDeclaration declaration && declaration.isEnum()) {
            return asClass(declaration);
        }
        AxlType narrowed = AxlNarrowing.typeAt(place, variable);
        if (narrowed != null) {
            return classOf(narrowed);
        }
        return variable.type() != null ? classOf(variable.type()) : initializerType(variable);
    }

    /** The class of a value without a written type, from its initializer; `val a = a` refers to itself. */
    private static @Nullable PsiClass initializerType(AxlVariable variable) {
        PsiElement initializer = variable.initializer();
        return initializer == null ? null
                : RecursionManager.doPreventingRecursion(variable, false, () -> typeOf(initializer));
    }

    static @Nullable PsiClass classOf(AxlType type) {
        String builtin = BUILTIN_TYPES.get(type.name());
        if (builtin != null) {
            return findClass(type, builtin);
        }
        AxlReferenceExpression reference = type.reference();
        PsiElement target = reference == null || reference.getReference() == null ? null : reference.getReference().resolve();
        return asClass(target);
    }

    private static @Nullable PsiClass classOf(@Nullable PsiType type) {
        return type instanceof PsiClassType classType ? classType.resolve() : null;
    }

    /** Locals, parameters, top-level declarations and imports visible at a place, innermost first. */
    static List<PsiElement> visibleDeclarations(PsiElement place) {
        List<PsiElement> result = new ArrayList<>(localDeclarations(place));
        AxlFile file = file(place);
        if (file != null) {
            result.addAll(file.functions());
            result.addAll(file.values());
            result.addAll(file.typeDeclarations());
            result.addAll(file.imports());
        }
        return result;
    }

    /** Declarations in enclosing blocks before the place, lambda and function parameters. */
    private static List<PsiElement> localDeclarations(PsiElement place) {
        List<PsiElement> result = new ArrayList<>();
        PsiElement child = place;
        for (PsiElement parent = place.getParent(); parent != null && !(parent instanceof AxlFile); parent = parent.getParent()) {
            var type = parent.getNode().getElementType();
            if (type == AxlElementTypes.BLOCK || type == AxlElementTypes.LAMBDA || type == AxlElementTypes.ATTEMPT) {
                List<PsiElement> before = new ArrayList<>();
                for (PsiElement sibling = parent.getFirstChild(); sibling != null && sibling != child; sibling = sibling.getNextSibling()) {
                    if (sibling instanceof AxlVariable) {
                        before.addFirst(sibling);
                    }
                }
                result.addAll(before);
            } else if (type == AxlElementTypes.FOR && child.getNode().getElementType() == AxlElementTypes.BLOCK) {
                result.addAll(PsiTreeUtil.getChildrenOfTypeAsList(parent, AxlVariable.class));
            } else if (type == AxlElementTypes.WHEN && child.getNode().getElementType() == AxlElementTypes.WHEN_ARM) {
                result.addAll(PsiTreeUtil.getChildrenOfTypeAsList(parent, AxlVariable.class));
            } else if (parent instanceof AxlFunction function) {
                result.addAll(function.parameters());
            } else if (parent instanceof AxlTypeDeclaration declaration && type == AxlElementTypes.CLASS) {
                result.addAll(declaration.fields());
                result.addAll(declaration.methods());
            }
            child = parent;
        }
        return result;
    }

    static @Nullable PsiClass findClass(PsiElement place, String qualifiedName) {
        if (qualifiedName.isEmpty()) {
            return null;
        }
        GlobalSearchScope scope = place.getResolveScope();
        return JavaPsiFacade.getInstance(place.getProject()).findClass(qualifiedName, scope);
    }

    private static @Nullable AxlFile file(PsiElement place) {
        return place.getContainingFile() instanceof AxlFile file ? file : null;
    }
}
