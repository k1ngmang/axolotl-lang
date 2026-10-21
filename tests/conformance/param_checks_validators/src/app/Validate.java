package app;

public final class Validate {
    private Validate() {}

    public static <T> T notNull(T object, String message, Object... values) {
        if (object == null) {
            throw new IllegalArgumentException(String.format(message, values));
        }
        return object;
    }

    public static String nonEmpty(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("empty text");
        }
        return text;
    }

    public static int length(String text) {
        return text == null ? -1 : text.length();
    }
}
