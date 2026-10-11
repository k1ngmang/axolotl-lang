package lib;

public final class Texts {
    private Texts() {}

    public static String join(String[] parts) {
        return String.join("+", parts);
    }

    public static int count(String... parts) {
        return parts.length;
    }
}
