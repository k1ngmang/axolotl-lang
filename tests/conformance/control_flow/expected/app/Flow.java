package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Flow {
    private Flow() {}
    public static String classify(int n) {
        if (n < 0) {
            return "negative";
        } else if (n == 0) {
            return "zero";
        } else {
            return "positive";
        }
    }
    public static void main(String[] args) {
        var i = -1;
        while (i <= 1) {
            System.out.println(classify(i));
            i = i + 1;
        }
        final long big = 10000000000L;
        final double ratio = 1.5;
        final boolean flag = !false && big > 1L || ratio < 0.0;
        System.out.println(flag);
        System.out.println(-big % 7);
    }
}
