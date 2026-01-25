package org.axl.intellij.java;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiConditionalExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiIntersectionType;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.impl.source.resolve.FileContextUtil;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.axl.compiler.JavaTypes;
import org.axl.intellij.psi.AxlFile;

/**
 * Types of top-level values from the IDE's Java model instead of javac, so no build is needed. The
 * results follow the build's javac version: the same type text and the same nullable marker.
 */
public final class PsiJavaTypes implements JavaTypes {

    private final AxlFile context;

    public PsiJavaTypes(AxlFile context) {
        this.context = context;
    }

    /**
     * The translator calls this on a thread of its own while the caller holds the read lock, so the
     * PSI is read in a read action of this thread. It cannot wait for a pending write action, which
     * waits for the caller, so the caller is canceled instead.
     */
    @Override
    public Map<String, String> localTypes(String qualifiedClassName, String javaSource) {
        Map<String, String> types = new LinkedHashMap<>();
        Runnable read = () -> types.putAll(readTypes(qualifiedClassName, javaSource));
        if (!ApplicationManagerEx.getApplicationEx().tryRunReadAction(read)) {
            throw new ProcessCanceledException();
        }
        return types;
    }

    private Map<String, String> readTypes(String qualifiedClassName, String javaSource) {
        String name = qualifiedClassName.substring(qualifiedClassName.lastIndexOf('.') + 1) + ".java";
        PsiJavaFile java = (PsiJavaFile) PsiFileFactory.getInstance(context.getProject())
                .createFileFromText(name, JavaLanguage.INSTANCE, javaSource);
        java.putUserData(FileContextUtil.INJECTED_IN_ELEMENT, SmartPointerManager.createPointer(context));
        Map<String, String> types = new LinkedHashMap<>();
        for (PsiClass cls : java.getClasses()) {
            for (PsiMethod probe : cls.findMethodsByName(PROBE_METHOD, false)) {
                // For a later local initialized from a nullable one.
                Set<PsiVariable> nullableLocals = new HashSet<>();
                for (PsiLocalVariable local : PsiTreeUtil.findChildrenOfType(probe, PsiLocalVariable.class)) {
                    String type = text(local.getType());
                    if (type == null) {
                        continue;
                    }
                    boolean nullable = isNullable(local.getInitializer(), nullableLocals);
                    if (nullable) {
                        nullableLocals.add(local);
                    }
                    types.put(local.getName(), nullable ? NULLABLE + type : type);
                }
            }
        }
        return types;
    }

    /** Java source text for a type as javac writes it, or null when it has none (intersections). */
    private static String text(PsiType type) {
        if (!type.isValid() || type instanceof PsiIntersectionType) {
            return null;
        }
        String text = type.getCanonicalText(true);
        if (text.contains("&")) {
            return null;
        }
        return text.replace("@org.jspecify.annotations.", "@").replaceAll("\\bjava\\.lang\\.(?=[@A-Z])", "");
    }

    /** Whether an initializer may be null as far as its declarations say, as the javac version decides. */
    private static boolean isNullable(PsiExpression expression, Set<PsiVariable> nullableLocals) {
        return switch (expression) {
            case PsiParenthesizedExpression p -> isNullable(p.getExpression(), nullableLocals);
            case PsiConditionalExpression c -> isNullable(c.getThenExpression(), nullableLocals)
                    || isNullable(c.getElseExpression(), nullableLocals);
            case PsiLiteralExpression l -> l.textMatches("null");
            case PsiMethodCallExpression call -> isNullableCall(call, nullableLocals) || hasNullableType(call);
            case PsiReferenceExpression ref -> {
                PsiElement target = ref.resolve();
                yield nullableLocals.contains(target) || target instanceof PsiModifierListOwner owner && declaredNullable(owner)
                        || hasNullableType(ref);
            }
            case null, default -> false;
        };
    }

    private static boolean isNullableCall(PsiMethodCallExpression call, Set<PsiVariable> nullableLocals) {
        PsiMethod method = call.resolveMethod();
        if (method == null) {
            return false;
        }
        PsiClass owner = method.getContainingClass();
        PsiExpression[] arguments = call.getArgumentList().getExpressions();
        // `a ?: b` is Nulls.or(a, () -> b): null only when the fallback may be.
        if (owner != null && "axl.lang.Nulls".equals(owner.getQualifiedName()) && method.getName().equals("or")
                && arguments.length == 2 && arguments[1] instanceof PsiLambdaExpression lambda
                && lambda.getBody() instanceof PsiExpression body) {
            return isNullable(body, nullableLocals);
        }
        return declaredNullable(method);
    }

    private static boolean declaredNullable(PsiModifierListOwner owner) {
        PsiType type = owner instanceof PsiMethod method ? method.getReturnType()
                : owner instanceof PsiVariable variable ? variable.getType() : null;
        return owner.getModifierList() != null && hasNullable(owner.getModifierList().getAnnotations())
                || type != null && hasNullable(type.getAnnotations());
    }

    /** `xs.get(0)` on a `List<String?>`: the type argument's annotation reaches the result. */
    private static boolean hasNullableType(PsiExpression expression) {
        PsiType type = expression.getType();
        return type != null && hasNullable(type.getAnnotations());
    }

    private static boolean hasNullable(PsiAnnotation[] annotations) {
        return Arrays.stream(annotations).anyMatch(a -> a.getQualifiedName() != null
                && (a.getQualifiedName().equals("Nullable") || a.getQualifiedName().endsWith(".Nullable")));
    }
}
