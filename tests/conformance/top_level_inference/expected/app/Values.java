package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;
import java.util.ArrayList;
import static util.Strings.shout;

@NullMarked public final class Values {
    private Values() {}
    public static final int limit = 10;
    public static final long big = 10000000000L;
    public static final double ratio = limit * 1.5;
    public static final String label = "limit: " + limit;
    public static final boolean enabled = limit > 5 && !false;
    public static final StringBuilder builder = new StringBuilder("x");
    public static final ArrayList<String> names = new ArrayList<String>();
    public static final int doubled = twice(limit);
    public static final int maxValue = Integer.MAX_VALUE;
    public static final List<String> letters = List.of("a", "b");
    public static final String loud = shout("hi");
    public static final String exclaimed = util.Strings.suffix + "?";
    public static final @Nullable String missing = null;
    public static final String fallback = Nulls.<String>or(missing, () -> "none");
    public static int twice(int n) {
        return n * 2;
    }
    public static void main(String[] args) {
        System.out.println(limit + 1);
        System.out.println(big);
        System.out.println(ratio);
        System.out.println(label);
        System.out.println(enabled);
        System.out.println(builder.append("y"));
        System.out.println(names.size());
        System.out.println(doubled);
        System.out.println(maxValue);
        System.out.println(letters.get(1).toUpperCase());
        System.out.println(loud);
        System.out.println(exclaimed);
        System.out.println(fallback.length());
    }
}
