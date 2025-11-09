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

/**
 * The command-line tools bundled with the plugin (`server/lib`): the compiler with Error Prone and
 * the language server. They run in their own JVM, which gets the options Error Prone needs.
 */
public final class AxlServer {

    private static final List<String> COMPILER_PACKAGES = List.of("api", "code", "comp", "file", "main", "model",
            "parser", "processing", "tree", "util");

    private static final Key<Boolean> LANGUAGE_SERVER_STARTED = Key.create("axl.languageServerStarted");

    private AxlServer() {}

    public static void markLanguageServerStarted(Project project) {
        project.putUserData(LANGUAGE_SERVER_STARTED, true);
    }

    public static boolean languageServerStarted(Project project) {
        return Boolean.TRUE.equals(project.getUserData(LANGUAGE_SERVER_STARTED));
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
        command.add(libDir().resolve("*").toString());
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
