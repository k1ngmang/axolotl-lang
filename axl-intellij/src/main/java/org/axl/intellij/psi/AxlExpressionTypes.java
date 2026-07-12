package org.axl.intellij.psi;

import com.intellij.openapi.util.RecursionManager;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.LambdaUtil;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiWildcardType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.psi.tree.IElementType;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.parser.AxlElementTypes;
import org.jetbrains.annotations.Nullable;

/**
 * The Java types of expressions with their type arguments, as far as they can be told without full
 * inference: `List.of("a")` is a `List<String>`, so `xs.get(0)` is a `String`, and so are `it`, a
 * lambda parameter and a loop variable over it.
 */
final class AxlExpressionTypes {

    /** Pipe stages after which the elements have the same type as before. */
    private static final Set<String> KEEPING = Set.of("filter", "peek", "sorted", "distinct", "limit", "skip");
    /** Stages that end a pipe. */
    private static final Set<String> ENDING = Set.of("forEach", "anyMatch", "allMatch", "noneMatch", "count", "first");

    private AxlExpressionTypes() {}

    static @Nullable PsiType typeOf(PsiElement expression) {
        IElementType kind = expression.getNode().getElementType();
        if (kind == AxlElementTypes.PARENTHESIZED || kind == AxlElementTypes.NOT_NULL) {
            AxlPsiElement inner = PsiTreeUtil.getChildOfType(expression, AxlPsiElement.class);
            return inner == null ? null : typeOf(inner);
        }
        if (kind == AxlElementTypes.LITERAL) {
            return literalType(expression);
        }
        if (kind == AxlElementTypes.GENERIC_NEW) {
            return genericNew(expression);
        }
        if (kind == AxlElementTypes.CLASS_LITERAL) {
            return classLiteral(expression);
        }
        if (kind == AxlElementTypes.PIPE) {
            return pipeType(expression);
        }
        if (expression instanceof AxlReferenceExpression ref) {
            PsiElement target = ref.getReference() == null ? null : ref.getReference().resolve();
            return target == null ? null : referenceType(ref, target);
        }
        if (kind == AxlElementTypes.CALL && expression.getFirstChild() instanceof AxlReferenceExpression callee) {
            PsiElement target = callee.getReference() == null ? null : callee.getReference().resolve();
            return target == null ? null : callType(callee, target, expression.getLastChild());
        }
        return null;
    }

    /** `String.class` is a `Class<String>`, `Int.class` a `Class<Integer>`. */
    private static @Nullable PsiType classLiteral(PsiElement literal) {
        PsiElement name = literal.getFirstChild();
        PsiClass cls = name instanceof AxlReferenceExpression ref && ref.getReference() != null
                ? AxlResolver.asClass(ref.getReference().resolve()) : null;
        PsiClass classClass = JavaPsiFacade.getInstance(literal.getProject()).findClass("java.lang.Class", literal.getResolveScope());
        if (cls == null || classClass == null) {
            return null;
        }
        return factory(literal).createType(classClass, factory(literal).createType(cls));
    }

    private static @Nullable PsiType referenceType(AxlReferenceExpression ref, PsiElement target) {
        return switch (target) {
            case AxlVariable variable -> variableType(ref, variable);
            case PsiField field -> memberSubstitutor(ref, field.getContainingClass()).substitute(field.getType());
            default -> target.getNode() != null && target.getNode().getElementType() == AxlElementTypes.LAMBDA
                    ? lambdaParameterType(target, 0) : null;
        };
    }

    private static @Nullable PsiType callType(AxlReferenceExpression callee, PsiElement target, PsiElement arguments) {
        PsiClass constructed = AxlResolver.asClass(target);
        if (constructed != null) {
            return factory(callee).createType(constructed);
        }
        if (target instanceof AxlFunction function) {
            return function.returnType() == null ? null : of(function.returnType());
        }
        if (!(target instanceof PsiMethod method) || method.getReturnType() == null) {
            return null;
        }
        PsiSubstitutor substitutor = memberSubstitutor(callee, method.getContainingClass()).putAll(inferred(method, arguments));
        return substitutor.substitute(method.getReturnType());
    }

    /** The type arguments of the class of a member, from the type of the expression before the dot. */
    private static PsiSubstitutor memberSubstitutor(AxlReferenceExpression ref, @Nullable PsiClass owner) {
        PsiElement qualifier = ref.qualifier();
        PsiType type = qualifier == null || owner == null ? null : typeOf(qualifier);
        if (!(type instanceof PsiClassType classType) || classType.resolve() == null) {
            return PsiSubstitutor.EMPTY;
        }
        return TypeConversionUtil.getSuperClassSubstitutor(owner, classType);
    }

    /** The type variables of a generic method, from the types of the call's arguments, varargs too. */
    private static PsiSubstitutor inferred(PsiMethod method, PsiElement arguments) {
        PsiParameter[] parameters = method.getParameterList().getParameters();
        List<AxlPsiElement> values = arguments == null || arguments.getNode().getElementType() != AxlElementTypes.ARGUMENTS
                ? List.of() : PsiTreeUtil.getChildrenOfTypeAsList(arguments, AxlPsiElement.class);
        boolean fits = values.size() == parameters.length || method.isVarArgs() && values.size() >= parameters.length - 1;
        if (method.getTypeParameters().length == 0 || values.isEmpty() || !fits) {
            return PsiSubstitutor.EMPTY;
        }
        PsiType[] declared = new PsiType[values.size()];
        PsiType[] actual = new PsiType[values.size()];
        for (int i = 0; i < values.size(); i++) {
            PsiType parameter = parameters[Math.min(i, parameters.length - 1)].getType();
            boolean spread = method.isVarArgs() && i >= parameters.length - 1 && parameter instanceof PsiArrayType;
            declared[i] = spread ? ((PsiArrayType) parameter).getComponentType() : parameter;
            actual[i] = boxed(values.get(i), typeOf(values.get(i)));
        }
        return JavaPsiFacade.getInstance(method.getProject()).getResolveHelper()
                .inferTypeArguments(method.getTypeParameters(), declared, actual, LanguageLevel.HIGHEST);
    }

    private static @Nullable PsiType variableType(PsiElement place, AxlVariable variable) {
        if (variable.getParent() instanceof AxlTypeDeclaration declaration && declaration.isEnum()) {
            PsiClass cls = AxlResolver.asClass(declaration);
            return cls == null ? null : factory(variable).createType(cls);
        }
        AxlType narrowed = AxlNarrowing.typeAt(place, variable);
        if (narrowed != null || variable.type() != null) {
            return of(narrowed != null ? narrowed : variable.type());
        }
        IElementType kind = variable.getNode().getElementType();
        if (kind == AxlElementTypes.LOOP_VARIABLE) {
            AxlPsiElement iterable = PsiTreeUtil.getNextSiblingOfType(variable, AxlPsiElement.class);
            return iterable == null ? null : elementType(typeOf(iterable));
        }
        if (kind == AxlElementTypes.LAMBDA_PARAMETER) {
            return parameterType(variable);
        }
        AxlPsiElement initializer = variable.initializer();
        return initializer == null ? null : RecursionManager.doPreventingRecursion(variable, false, () -> typeOf(initializer));
    }

    /** The error of `attempt ... else e ->` is an exception; a lambda parameter has the type the lambda is given. */
    private static @Nullable PsiType parameterType(AxlVariable parameter) {
        PsiElement owner = parameter.getParent();
        if (owner.getNode().getElementType() == AxlElementTypes.ATTEMPT) {
            PsiClass exception = AxlResolver.findClass(parameter, "java.lang.Exception");
            return exception == null ? null : factory(parameter).createType(exception);
        }
        List<AxlVariable> parameters = PsiTreeUtil.getChildrenOfTypeAsList(owner, AxlVariable.class);
        return lambdaParameterType(owner, parameters.indexOf(parameter));
    }

    /**
     * The type a lambda's parameter takes: for a pipe stage the element type, of the pipe's source as
     * the stages before leave it; for an argument of a Java method, from the interface it expects.
     */
    private static @Nullable PsiType lambdaParameterType(PsiElement lambda, int index) {
        PsiElement parent = lambda.getParent();
        IElementType kind = parent == null ? null : parent.getNode().getElementType();
        if (kind == AxlElementTypes.ARGUMENTS) {
            return argumentLambdaParameterType(lambda, index);
        }
        if (index != 0 || kind != AxlElementTypes.PIPE_STAGE) {
            return null;
        }
        PsiElement pipe = parent.getParent();
        PsiType element = elementType(typeOf(pipe.getFirstChild()));
        for (PsiElement before = pipe.getFirstChild().getNextSibling(); before != null && before != parent; before = before.getNextSibling()) {
            if (before.getNode().getElementType() == AxlElementTypes.PIPE_STAGE) {
                element = afterStage(before, element);
            }
        }
        return element;
    }

    /** `xs.forEach({ println(it) })`: the parameter of the functional interface, with the receiver's type arguments. */
    private static @Nullable PsiType argumentLambdaParameterType(PsiElement lambda, int index) {
        PsiElement arguments = lambda.getParent();
        PsiElement call = arguments.getParent();
        if (call == null || call.getNode().getElementType() != AxlElementTypes.CALL
                || !(call.getFirstChild() instanceof AxlReferenceExpression callee) || callee.getReference() == null) {
            return null;
        }
        int position = PsiTreeUtil.getChildrenOfTypeAsList(arguments, AxlPsiElement.class).indexOf(lambda);
        if (!(callee.getReference().resolve() instanceof PsiMethod method) || position < 0
                || position >= method.getParameterList().getParametersCount()) {
            return null;
        }
        PsiType expected = memberSubstitutor(callee, method.getContainingClass())
                .substitute(method.getParameterList().getParameters()[position].getType());
        PsiType parameter = LambdaUtil.getLambdaParameterFromType(expected, index);
        return parameter instanceof PsiWildcardType wildcard ? wildcard.getBound() : parameter;
    }

    /** What a pipe gives: the list of the elements its stages leave, or the result of its ending stage. */
    private static @Nullable PsiType pipeType(PsiElement pipe) {
        PsiType element = elementType(typeOf(pipe.getFirstChild()));
        String ending = "";
        for (PsiElement stage = pipe.getFirstChild().getNextSibling(); stage != null; stage = stage.getNextSibling()) {
            if (stage.getNode().getElementType() == AxlElementTypes.PIPE_STAGE) {
                ending = stage.getFirstChild().getText();
                element = ENDING.contains(ending) ? element : afterStage(stage, element);
            }
        }
        PsiElementFactory factory = factory(pipe);
        return switch (ending) {
            case "first" -> element;
            case "count" -> factory.createTypeByFQClassName("java.lang.Long", pipe.getResolveScope());
            case "anyMatch", "allMatch", "noneMatch" -> factory.createTypeByFQClassName("java.lang.Boolean", pipe.getResolveScope());
            case "forEach" -> null;
            default -> listOf(pipe, element);
        };
    }

    private static @Nullable PsiType listOf(PsiElement place, @Nullable PsiType element) {
        PsiClass list = AxlResolver.findClass(place, "java.util.List");
        return list == null || element == null ? null : factory(place).createType(list, element);
    }

    /** The element type after a stage: the same after a filter, the result of the lambda after `map`. */
    private static @Nullable PsiType afterStage(PsiElement stage, @Nullable PsiType element) {
        PsiElement name = stage.getFirstChild();
        if (name == null || KEEPING.contains(name.getText())) {
            return element;
        }
        PsiElement lambda = stage.getLastChild();
        if (!name.getText().equals("map") || lambda.getNode().getElementType() != AxlElementTypes.LAMBDA) {
            return null;
        }
        PsiElement last = lambda.getLastChild().getPrevSibling();
        while (last != null && !(last instanceof AxlPsiElement)) {
            last = last.getPrevSibling();
        }
        PsiElement value = last == null || last.getNode().getElementType() != AxlElementTypes.EXPRESSION_STATEMENT ? last : last.getFirstChild();
        // The elements of a stream are objects: `map { it.length() }` gives Integers.
        return value == null ? null : RecursionManager.doPreventingRecursion(lambda, false, () -> boxed(value, typeOf(value)));
    }

    /** The elements of an array, an `Iterable` or a stream; null for other types. */
    private static @Nullable PsiType elementType(@Nullable PsiType type) {
        if (type instanceof PsiArrayType array) {
            return array.getComponentType();
        }
        PsiType element = PsiUtil.extractIterableTypeParameter(type, false);
        return element != null ? element : PsiUtil.substituteTypeParameter(type, "java.util.stream.BaseStream", 0, false);
    }

    /** A written type with its type arguments, as `List<String>`; raw when they do not resolve. */
    static @Nullable PsiType of(AxlType type) {
        PsiClass cls = AxlResolver.classOf(type);
        if (cls == null) {
            return null;
        }
        PsiElement arguments = type.getNode().findChildByType(AxlElementTypes.TYPE_ARGUMENTS) == null ? null
                : type.getNode().findChildByType(AxlElementTypes.TYPE_ARGUMENTS).getPsi();
        List<AxlType> written = arguments == null ? List.of() : PsiTreeUtil.getChildrenOfTypeAsList(arguments, AxlType.class);
        PsiType[] types = written.stream().map(AxlExpressionTypes::of).toArray(PsiType[]::new);
        boolean resolved = types.length == cls.getTypeParameters().length && Arrays.stream(types).allMatch(t -> t != null);
        return resolved && types.length > 0 ? factory(type).createType(cls, types) : factory(type).createType(cls);
    }

    private static @Nullable PsiType genericNew(PsiElement expression) {
        PsiElement name = expression.getFirstChild();
        PsiElement arguments = expression.getNode().findChildByType(AxlElementTypes.TYPE_ARGUMENTS) == null ? null
                : expression.getNode().findChildByType(AxlElementTypes.TYPE_ARGUMENTS).getPsi();
        String qualified = expression.getText().substring(0, arguments == null ? name.getTextLength()
                : arguments.getStartOffsetInParent()).strip();
        PsiClass cls = qualified.contains(".") ? AxlResolver.findClass(expression, qualified)
                : AxlResolver.asClass(AxlResolver.resolveName(expression, qualified).stream().findFirst().orElse(null));
        if (cls == null) {
            return null;
        }
        List<AxlType> written = arguments == null ? List.of() : PsiTreeUtil.getChildrenOfTypeAsList(arguments, AxlType.class);
        PsiType[] types = written.stream().map(AxlExpressionTypes::of).toArray(PsiType[]::new);
        boolean resolved = types.length == cls.getTypeParameters().length && Arrays.stream(types).allMatch(t -> t != null);
        return resolved ? factory(expression).createType(cls, types) : factory(expression).createType(cls);
    }

    private static @Nullable PsiType literalType(PsiElement literal) {
        IElementType token = literal.getFirstChild().getNode().getElementType();
        if (token == AxlTokenTypes.STRING) {
            return PsiType.getJavaLangString(literal.getManager(), literal.getResolveScope());
        }
        if (token == AxlTokenTypes.INT) {
            return literal.getText().toLowerCase(Locale.ROOT).endsWith("l") ? PsiTypes.longType() : PsiTypes.intType();
        }
        return token == AxlTokenTypes.FLOAT ? PsiTypes.doubleType() : null;
    }

    private static @Nullable PsiType boxed(PsiElement place, @Nullable PsiType type) {
        return type instanceof PsiPrimitiveType primitive ? primitive.getBoxedType(place) : type;
    }

    private static PsiElementFactory factory(PsiElement place) {
        return JavaPsiFacade.getElementFactory(place.getProject());
    }
}
