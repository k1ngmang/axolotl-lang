package org.axl.intellij.lsp;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
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
 * modules it depends on and its libraries, as `axl check` gets them in Community.
 */
final class AxlLspModules {

    private AxlLspModules() {}

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
        Set<String> sources = new LinkedHashSet<>(paths(module, own.stream()));
        if (sources.isEmpty()) {
            return;
        }
        if (test) {
            sources.addAll(paths(module, production.stream()));
        }
        sources.addAll(paths(module, Arrays.stream(OrderEnumerator.orderEntries(module).withoutSdk().withoutLibraries()
                .withoutModuleSourceEntries().productionOnly().recursively().sources().getRoots())));
        OrderEnumerator libraries = OrderEnumerator.orderEntries(module).withoutSdk().librariesOnly().recursively();
        VirtualFile[] content = roots.getContentRoots();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("root", content.length > 0 ? content[0].getPath() : sources.iterator().next());
        entry.put("sourceRoots", List.copyOf(sources));
        entry.put("javaSourceRoots", List.copyOf(sources));
        entry.put("classpath", (test ? libraries : libraries.productionOnly()).getPathsList().getPathList());
        result.add(entry);
    }

    /** Without excluded roots, such as the Java the build generates from .axl files, which would clash with them. */
    private static List<String> paths(Module module, Stream<VirtualFile> roots) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(module.getProject());
        return roots.filter(root -> !index.isExcluded(root)).map(VirtualFile::getPath).toList();
    }
}
