package org.axl.intellij;

import com.intellij.lang.Language;

public final class AxlLanguage extends Language {

    public static final AxlLanguage INSTANCE = new AxlLanguage();

    private AxlLanguage() {
        super("Axolotl");
    }
}
