package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var words = List.of("1", "x", "3", "-4");
        final var out = new ArrayList<Integer>();
        for (final var s : words) {
            try {
                out.add(Integer.parseInt(s));
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        System.out.println(out);
        var i = 0;
        final var signs = new ArrayList<String>();
        while (i < words.size()) {
            final var s = words.get(i);
            i += 1;
            if (s.startsWith("-")) {
                try {
                    signs.add("minus " + Integer.parseInt(s.substring(1)));
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                }
            } else {
                try {
                    Integer.parseInt(s);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    continue;
                }
                signs.add("plus " + s);
            }
        }
        System.out.println(signs);
    }
}
