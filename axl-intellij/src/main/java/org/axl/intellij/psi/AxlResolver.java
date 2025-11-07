package org.axl.intellij.psi;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.parser.AxlElementTypes;
import org.jetbrains.annotations.Nullable;

/** Name lookup in .axl files: scopes, top-level declarations, imports and Java classes. */
public final class AxlResolver {

    private static final Map<String, String> BUILTIN_TYPES = Map.of(
            "Int", "java.lang.Integer", "Long", "java.lang.Long", "Boolean", "java.lang.Boolean", "Double", "java.lang.Double");
    private static final Map<String, String> JAVA_TYPES = Map.of("int", "Int", "Integer", "Int", "long", "Long",
            "boolean", "Boolean", "double", "Double");
    private static final Map<IElementType, String> LITERAL_TYPES = Map.of(AxlTokenTypes.STRING, "String",
            AxlTokenTypes.INT, "Int", AxlTokenTypes.FLOAT, "Double", AxlTokenTypes.of(AxolotlLexer.TRUE), "Boolean",
            AxlTokenTypes.of(AxolotlLexer.FALSE), "Boolean");

    private AxlResolver() {}

    static @Nullable PsiElement resolveName(PsiElement place, String name) {
        for (PsiElement local : localDeclarations(place)) {
            if (local instanceof AxlNamedElement named && name.equals(named.getName())) {
                return named;
            }
        }
        AxlFile file = file(place);
        if (file == null) {
            return null;
        }
        List<AxlFunction> functions = file.functions().stream().filter(f -> name.equals(f.getName())).toList();
        if (!functions.isEmpty()) {
            return overloadsFor(place, functions).getFirst();
        }
        for (AxlVariable value : file.values()) {
            if (name.equals(value.getName())) {
                return value;
            }
        }
        boolean inImport = PsiTreeUtil.getParentOfType(place, AxlImport.class) != null;
        for (AxlImport imp : file.imports()) {
            if (inImport || !imp.simpleName().equals(name)) {
                continue;
            }
            String qualified = imp.qualifiedName();
            if (imp.kind() == AxlImport.Kind.TYPE) {
                return findClass(place, qualified);
            }
            PsiClass owner = findClass(place, qualified.substring(0, Math.max(0, qualified.lastIndexOf('.'))));
            List<PsiElement> members = owner == null ? List.of() : overloadsFor(place, members(owner, name));
            return members.isEmpty() ? null : members.getFirst();
        }
        String pkg = file.packageName();
        String builtin = BUILTIN_TYPES.get(name);
        for (String candidate : List.of(builtin != null ? builtin : pkg.isEmpty() ? name : pkg + "." + name,
                "java.lang." + name, "axl.lang." + name)) {
            PsiClass cls = findClass(place, candidate);
            if (cls != null) {
                return cls;
            }
        }
        return JavaPsiFacade.getInstance(place.getProject()).findPackage(name);
    }

    static @Nullable PsiElement inPackage(PsiElement place, PsiPackage pkg, String name) {
        String qualified = pkg.getQualifiedName().isEmpty() ? name : pkg.getQualifiedName() + "." + name;
        PsiClass cls = findClass(place, qualified);
        return cls != null ? cls : JavaPsiFacade.getInstance(place.getProject()).findPackage(qualified);
    }

    /** Methods and fields of a class by name; members of Axolotl files resolve to their declarations. */
    static List<PsiElement> members(PsiClass cls, String name) {
        LinkedHashSet<PsiElement> result = new LinkedHashSet<>();
        for (PsiMethod method : cls.findMethodsByName(name, true)) {
            result.add(source(method));
        }
        PsiField field = cls.findFieldByName(name, true);
        if (field != null) {
            result.add(source(field));
        }
        return new ArrayList<>(result);
    }

    /**
     * Of several functions or methods with a name, those that a call's arguments fit: by number,
     * then by the types of literals and typed variables. All of them for a name that is not called.
     */
    static List<PsiElement> overloadsFor(PsiElement place, List<? extends PsiElement> candidates) {
        List<PsiElement> arguments = callArguments(place);
        if (arguments == null || candidates.size() < 2) {
            return new ArrayList<>(candidates);
        }
        List<PsiElement> sameCount = new ArrayList<>();
        for (PsiElement candidate : candidates) {
            List<String> types = parameterTypes(candidate);
            if (types != null && types.size() == arguments.size()) {
                sameCount.add(candidate);
            }
        }
        List<PsiElement> fitting = sameCount.stream().filter(c -> fits(parameterTypes(c), arguments)).toList();
        return !fitting.isEmpty() ? fitting : !sameCount.isEmpty() ? sameCount : new ArrayList<>(candidates);
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

    /** Simple names of the parameter types, as Axolotl writes them; null when the element is not callable. */
    private static @Nullable List<String> parameterTypes(PsiElement callable) {
        if (callable instanceof AxlFunction function) {
            return function.parameters().stream().map(p -> p.type() == null ? null : p.type().name()).toList();
        }
        if (callable instanceof PsiMethod method) {
            return Arrays.stream(method.getParameterList().getParameters()).map(p -> {
                String text = p.getType().getPresentableText();
                String simple = text.contains("<") ? text.substring(0, text.indexOf('<')) : text;
                return JAVA_TYPES.getOrDefault(simple, simple);
            }).toList();
        }
        return null;
    }

    private static boolean fits(List<String> parameterTypes, List<PsiElement> arguments) {
        for (int i = 0; i < arguments.size(); i++) {
            String argument = argumentType(arguments.get(i));
            String parameter = parameterTypes.get(i);
            if (argument != null && parameter != null && !argument.equals(parameter)) {
                return false;
            }
        }
        return true;
    }

    /** The type of a literal or of a variable with a written type, or null when it takes inference. */
    private static @Nullable String argumentType(PsiElement argument) {
        if (argument.getNode().getElementType() == AxlElementTypes.LITERAL) {
            IElementType token = argument.getFirstChild().getNode().getElementType();
            return LITERAL_TYPES.get(token);
        }
        if (argument instanceof AxlReferenceExpression ref && ref.qualifier() == null && ref.getReference() != null
                && ref.getReference().resolve() instanceof AxlVariable variable && variable.type() != null) {
            return variable.type().name();
        }
        return null;
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
            if (target instanceof PsiClass constructed) {
                return constructed;
            }
        }
        return switch (target) {
            case AxlVariable variable -> variable.type() == null ? null : classOf(variable.type());
            case AxlFunction function -> function.returnType() == null ? null : classOf(function.returnType());
            case PsiField field -> classOf(field.getType());
            case PsiMethod method -> classOf(method.getReturnType());
            case null, default -> null;
        };
    }

    static @Nullable PsiClass classOf(AxlType type) {
        String builtin = BUILTIN_TYPES.get(type.name());
        if (builtin != null) {
            return findClass(type, builtin);
        }
        AxlReferenceExpression reference = type.reference();
        PsiElement target = reference == null || reference.getReference() == null ? null : reference.getReference().resolve();
        return target instanceof PsiClass cls ? cls : null;
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
            } else if (parent instanceof AxlFunction function) {
                result.addAll(function.parameters());
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
