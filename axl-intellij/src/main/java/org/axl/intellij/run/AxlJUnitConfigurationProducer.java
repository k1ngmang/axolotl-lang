package org.axl.intellij.run;

import com.intellij.execution.JavaExecutionUtil;
import com.intellij.execution.PsiLocation;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.execution.junit.JUnitConfigurationType;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlTypeDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs a class with `@Test` methods, or one of them, as a JUnit configuration of the generated
 * class: the file's class, or `Outer$Inner` for a class declared in a file of another name. As
 * for `fn main()`, a delegated Gradle or Maven build compiles the .axl sources first.
 */
public final class AxlJUnitConfigurationProducer extends LazyRunConfigurationProducer<JUnitConfiguration> {

    /** A test method with its class, or a test class alone, at the place of the context. */
    private record Target(PsiElement declaration, PsiClass cls, @Nullable PsiMethod method) {}

    @Override
    public @NotNull ConfigurationFactory getConfigurationFactory() {
        return JUnitConfigurationType.getInstance().getConfigurationFactories()[0];
    }

    @Override
    protected boolean setupConfigurationFromContext(@NotNull JUnitConfiguration configuration,
                                                    @NotNull ConfigurationContext context, @NotNull Ref<PsiElement> source) {
        Target target = target(context);
        if (target == null) {
            return false;
        }
        if (target.method() != null) {
            configuration.beMethodConfiguration(PsiLocation.fromPsiElement(context.getProject(), target.method()));
        } else {
            configuration.beClassConfiguration(target.cls());
        }
        if (configuration.getConfigurationModule().getModule() == null) {
            configuration.setModule(context.getModule());
        }
        configuration.setGeneratedName();
        source.set(target.declaration());
        return true;
    }

    @Override
    public boolean isConfigurationFromContext(@NotNull JUnitConfiguration configuration, @NotNull ConfigurationContext context) {
        Target target = target(context);
        if (target == null) {
            return false;
        }
        JUnitConfiguration.Data data = configuration.getPersistentData();
        String className = JavaExecutionUtil.getRuntimeQualifiedName(target.cls());
        if (className == null || !className.equals(data.getMainClassName())) {
            return false;
        }
        if (target.method() == null) {
            return JUnitConfiguration.TEST_CLASS.equals(data.TEST_OBJECT);
        }
        return JUnitConfiguration.TEST_METHOD.equals(data.TEST_OBJECT) && target.method().getName().equals(data.getMethodName());
    }

    /** The test method the context is in, otherwise the test class it is in. */
    private static @Nullable Target target(ConfigurationContext context) {
        PsiElement location = context.getPsiLocation();
        if (location == null || !(location.getContainingFile() instanceof AxlFile)) {
            return null;
        }
        AxlFunction function = PsiTreeUtil.getParentOfType(location, AxlFunction.class, false);
        PsiMethod method = function == null ? null : AxlTests.testMethod(function);
        if (method != null && method.getContainingClass() != null) {
            return new Target(function, method.getContainingClass(), method);
        }
        AxlTypeDeclaration declaration = PsiTreeUtil.getParentOfType(location, AxlTypeDeclaration.class, false);
        PsiClass cls = declaration == null ? null : AxlTests.testClass(declaration);
        return cls == null ? null : new Target(declaration, cls, null);
    }
}
