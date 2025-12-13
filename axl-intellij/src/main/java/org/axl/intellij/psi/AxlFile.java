package org.axl.intellij.psi;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import org.axl.intellij.AxlFileType;
import org.axl.intellij.AxlLanguage;
import org.axl.intellij.parser.AxlElementTypes;
import org.jetbrains.annotations.NotNull;

public final class AxlFile extends PsiFileBase {

    public AxlFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, AxlLanguage.INSTANCE);
    }

    @Override
    public @NotNull FileType getFileType() {
        return AxlFileType.INSTANCE;
    }

    public String packageName() {
        var pkg = getNode().findChildByType(AxlElementTypes.PACKAGE);
        var name = pkg == null ? null : pkg.findChildByType(AxlElementTypes.QUALIFIED_NAME);
        return name == null ? "" : name.getText();
    }

    /** The Java class the file becomes. */
    public String className() {
        String name = getName();
        return name.endsWith(".axl") ? name.substring(0, name.length() - 4) : name;
    }

    /** The path in its source root, which the translator checks against the package; the name outside source roots. */
    public String sourcePath() {
        VirtualFile file = getOriginalFile().getVirtualFile();
        VirtualFile root = file == null ? null : ProjectFileIndex.getInstance(getProject()).getSourceRootForFile(file);
        String relative = root == null ? null : VfsUtilCore.getRelativePath(file, root);
        return relative != null ? relative : getName();
    }

    public String qualifiedClassName() {
        String pkg = packageName();
        return pkg.isEmpty() ? className() : pkg + "." + className();
    }

    public List<AxlImport> imports() {
        return PsiTreeUtil.getChildrenOfTypeAsList(this, AxlImport.class);
    }

    public List<AxlFunction> functions() {
        return PsiTreeUtil.getChildrenOfTypeAsList(this, AxlFunction.class);
    }

    public List<AxlVariable> values() {
        return PsiTreeUtil.getChildrenOfTypeAsList(this, AxlVariable.class);
    }

    @Override
    public String toString() {
        return "Axolotl file";
    }
}
