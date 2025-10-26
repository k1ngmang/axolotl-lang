package org.axl.intellij;

import com.intellij.openapi.fileTypes.LanguageFileType;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

public final class AxlFileType extends LanguageFileType {

    public static final AxlFileType INSTANCE = new AxlFileType();

    private AxlFileType() {
        super(AxlLanguage.INSTANCE);
    }

    @Override
    public @NotNull String getName() {
        return "Axolotl";
    }

    @Override
    public @NotNull String getDescription() {
        return "Axolotl source";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "axl";
    }

    @Override
    public Icon getIcon() {
        return AxlIcons.FILE;
    }
}
