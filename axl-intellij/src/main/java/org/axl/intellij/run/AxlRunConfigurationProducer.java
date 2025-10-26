package org.axl.intellij.run;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.jetbrains.annotations.NotNull;

/**
 * Runs `fn main()` as a Java application of the generated class. With Gradle or Maven delegation,
 * the build tool compiles the .axl sources first.
 */
public final class AxlRunConfigurationProducer extends LazyRunConfigurationProducer<ApplicationConfiguration> {

    @Override
    public @NotNull ConfigurationFactory getConfigurationFactory() {
        return ApplicationConfigurationType.getInstance().getConfigurationFactories()[0];
    }

    @Override
    protected boolean setupConfigurationFromContext(@NotNull ApplicationConfiguration configuration,
                                                    @NotNull ConfigurationContext context, @NotNull Ref<PsiElement> source) {
        AxlFunction main = mainFunction(context);
        if (main == null) {
            return false;
        }
        AxlFile file = (AxlFile) main.getContainingFile();
        configuration.setMainClassName(file.qualifiedClassName());
        configuration.setModule(context.getModule());
        configuration.setName(file.className());
        source.set(main);
        return true;
    }

    @Override
    public boolean isConfigurationFromContext(@NotNull ApplicationConfiguration configuration, @NotNull ConfigurationContext context) {
        AxlFunction main = mainFunction(context);
        return main != null && ((AxlFile) main.getContainingFile()).qualifiedClassName().equals(configuration.getMainClassName());
    }

    private static AxlFunction mainFunction(ConfigurationContext context) {
        PsiElement location = context.getPsiLocation();
        if (location == null || !(location.getContainingFile() instanceof AxlFile file)) {
            return null;
        }
        AxlFunction function = PsiTreeUtil.getParentOfType(location, AxlFunction.class, false);
        if (function != null && function.isMain()) {
            return function;
        }
        return file.functions().stream().filter(AxlFunction::isMain).findFirst().orElse(null);
    }
}
