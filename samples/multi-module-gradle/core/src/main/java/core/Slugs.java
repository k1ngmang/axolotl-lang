package core;

import java.util.Locale;

/** Plain Java used from Axolotl code. */
public final class Slugs {
    private Slugs() {}

    public static String slug(String text) {
        return text.strip().toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    public static String firstWord(String slug) {
        int dash = slug.indexOf('-');
        return dash < 0 ? slug : slug.substring(0, dash);
    }
}
