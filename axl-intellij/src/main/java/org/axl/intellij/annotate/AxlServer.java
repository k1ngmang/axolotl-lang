package org.axl.intellij.annotate;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkType;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Key;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The command-line tools bundled with the plugin (`server/lib`): the compiler with Error Prone and
 * the language server. They run in their own JVM, which gets the options Error Prone needs.
 */
public final class AxlServer {

    private static final List<String> COMPILER_PACKAGES = List.of("api", "code", "comp", "file", "main", "model",
            "parser", "processing", "tree", "util");

    private static final Key<BooleanSupplier> LANGUAGE_SERVER = Key.create("axl.languageServer");

    private AxlServer() {}

    /** In Ultimate: running tells whether the language server runs, which then reports the module's errors. */
    public static void useLanguageServer(Project project, BooleanSupplier running) {
        project.putUserData(LANGUAGE_SERVER, running);
    }

    public static boolean languageServerRuns(Project project) {
        BooleanSupplier running = project.getUserData(LANGUAGE_SERVER);
        return running != null && running.getAsBoolean();
    }

    /** `java ... -cp server/lib/* mainClass`, with the module's JDK when it is one. */
    public static List<String> command(Module module, String mainClass) {
        List<String> command = new ArrayList<>();
        command.add(java(module));
        for (String p : COMPILER_PACKAGES) {
            command.add("--add-exports=jdk.compiler/com.sun.tools.javac." + p + "=ALL-UNNAMED");
            command.add("--add-opens=jdk.compiler/com.sun.tools.javac." + p + "=ALL-UNNAMED");
        }
        command.add("-cp");
        // `*` is a classpath wildcard, not a file name; Windows paths cannot contain it.
        command.add(libDir() + File.separator + "*");
        command.add(mainClass);
        return command;
    }

    private static Path libDir() {
        IdeaPluginDescriptor plugin = PluginManagerCore.getPlugin(PluginId.getId("org.axl.lang"));
        return plugin.getPluginPath().resolve("server").resolve("lib");
    }

    private static String java(Module module) {
        Sdk sdk = module == null ? null : ModuleRootManager.getInstance(module).getSdk();
        if (sdk != null && sdk.getSdkType() instanceof JavaSdkType type && type.getVMExecutablePath(sdk) != null) {
            return type.getVMExecutablePath(sdk);
        }
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }
}
