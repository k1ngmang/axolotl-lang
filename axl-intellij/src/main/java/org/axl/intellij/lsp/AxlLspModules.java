package org.axl.intellij.lsp;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The modules of a project as the language server's `modules` option describes them: for each
 * IntelliJ module its production sources and its test sources, each with the source roots of the
 * modules it depends on and its libraries, as `axl check` gets them in Community. Only its own Java
 * is compiled: the Java of the modules it depends on is read from their sources where newer than
 * their output, which is on the classpath, since it may need their own libraries or other compilers.
 */
final class AxlLspModules {

    private static final Key<List<Map<String, Object>>> STARTED_WITH = Key.create("axl.lspModules");

    private AxlLspModules() {}

    /** The modules for a server being started, remembered to tell later whether they changed. */
    static List<Map<String, Object>> forServer(Project project) {
        List<Map<String, Object>> modules = of(project);
        project.putUserData(STARTED_WITH, modules);
        return modules;
    }

    /** Whether the modules differ from those the server was started with. */
    static boolean changed(Project project) {
        return !of(project).equals(project.getUserData(STARTED_WITH));
    }

    /** Dependencies come before the modules that use them, so that a file belongs to its own module first. */
    static List<Map<String, Object>> of(Project project) {
        return ReadAction.compute(() -> {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Module module : ModuleManager.getInstance(project).getSortedModules()) {
                add(result, module, false);
                add(result, module, true);
            }
            return result;
        });
    }

    /** test: the module's test sources, which also see its production sources and test libraries. */
    private static void add(List<Map<String, Object>> result, Module module, boolean test) {
        ModuleRootManager roots = ModuleRootManager.getInstance(module);
        List<VirtualFile> production = Arrays.asList(roots.getSourceRoots(false));
        List<VirtualFile> own = test ? Arrays.stream(roots.getSourceRoots(true)).filter(r -> !production.contains(r)).toList()
                : production;
        List<String> ownPaths = paths(module, own.stream());
        if (ownPaths.isEmpty()) {
            return;
        }
        Set<String> dependencies = new LinkedHashSet<>(test ? paths(module, production.stream()) : List.of());
        dependencies.addAll(paths(module, Arrays.stream(OrderEnumerator.orderEntries(module).withoutSdk().withoutLibraries()
                .withoutModuleSourceEntries().productionOnly().recursively().sources().getRoots())));
        dependencies.removeAll(ownPaths);
        Set<String> sources = new LinkedHashSet<>(ownPaths);
        sources.addAll(dependencies);
        VirtualFile[] content = roots.getContentRoots();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("root", content.length > 0 ? content[0].getPath() : ownPaths.getFirst());
        entry.put("sourceRoots", List.copyOf(sources));
        entry.put("javaSourceRoots", ownPaths);
        entry.put("javaSourcepath", List.copyOf(dependencies));
        entry.put("classpath", classpath(module, test));
        result.add(entry);
    }

    /** The libraries, and the output of the modules depended on; for the tests, the module's production output too. */
    private static List<String> classpath(Module module, boolean test) {
        OrderEnumerator libraries = OrderEnumerator.orderEntries(module).withoutSdk().librariesOnly().recursively();
        OrderEnumerator outputs = OrderEnumerator.orderEntries(module).withoutSdk().withoutLibraries().productionOnly().recursively();
        List<String> result = new ArrayList<>((test ? libraries : libraries.productionOnly()).getPathsList().getPathList());
        result.addAll((test ? outputs : outputs.withoutModuleSourceEntries()).classes().getPathsList().getPathList());
        return result;
    }

    /** Without excluded roots, such as the Java the build generates from .axl files, which would clash with them. */
    private static List<String> paths(Module module, Stream<VirtualFile> roots) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(module.getProject());
        return roots.filter(root -> !index.isExcluded(root)).map(VirtualFile::getPath).toList();
    }
}
