package org.axl.intellij.java;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScopesCore;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.axl.compiler.Options;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.parse.SourceParser;
import org.axl.intellij.AxlFileType;
import org.axl.intellij.psi.AxlFile;
import org.jetbrains.annotations.Nullable;

/**
 * The options of a translation in the IDE: the types of top-level values from the IDE's Java model,
 * and the other .axl files of the module parsed, whose declarations the translator reads as the
 * build does, such as the values of an interface a class implements or a generic sealed type.
 */
public final class TranslationOptions {

    private TranslationOptions() {}

    public static Options of(AxlFile file) {
        return new Options(false).withJavaTypes(new PsiJavaTypes(file)).withSiblings(siblingsOf(file));
    }

    /**
     * The source roots the build translates a file with: the production roots of its module, and for
     * a test its test roots too, with the production roots of the modules it depends on, where Gradle
     * puts the main sources of a project.
     */
    public static List<VirtualFile> sourceRoots(Module module, VirtualFile file) {
        boolean test = ProjectFileIndex.getInstance(module.getProject()).isInTestSourceContent(file);
        Set<VirtualFile> roots = new LinkedHashSet<>(List.of(ModuleRootManager.getInstance(module).getSourceRoots(test)));
        if (test) {
            roots.addAll(List.of(OrderEnumerator.orderEntries(module).withoutSdk().withoutLibraries()
                    .withoutModuleSourceEntries().productionOnly().recursively().sources().getRoots()));
        }
        return new ArrayList<>(roots);
    }

    /** The other .axl files of those roots that parse, from the editor's text. */
    static List<SourceFile> siblingsOf(AxlFile file) {
        VirtualFile own = file.getOriginalFile().getVirtualFile();
        Module module = ModuleUtilCore.findModuleForPsiElement(file);
        List<SourceFile> result = new ArrayList<>();
        if (own == null || module == null) {
            return result;
        }
        List<VirtualFile> roots = sourceRoots(module, own);
        if (roots.isEmpty()) {
            return result;
        }
        PsiManager manager = PsiManager.getInstance(file.getProject());
        GlobalSearchScope scope = GlobalSearchScopesCore.directoriesScope(file.getProject(), true, roots.toArray(VirtualFile[]::new));
        for (VirtualFile other : FileTypeIndex.getFiles(AxlFileType.INSTANCE, scope)) {
            if (!other.equals(own) && manager.findFile(other) instanceof AxlFile sibling) {
                SourceFile parsed = parsed(sibling);
                if (parsed != null) {
                    result.add(parsed);
                }
            }
        }
        return result;
    }

    /** The file as the translator reads it, parsed again only when it changes; null while it does not parse. */
    private static @Nullable SourceFile parsed(AxlFile file) {
        return CachedValuesManager.getCachedValue(file, () -> CachedValueProvider.Result.create(
                SourceParser.parse(file.getName(), file.className(), file.getText()).file(), file));
    }
}
