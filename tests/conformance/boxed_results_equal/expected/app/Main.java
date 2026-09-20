package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var xs = List.of(1000, 1000, 7);
        System.out.println(((int) xs.get(0)) == ((int) xs.get(1)));
        System.out.println(((int) xs.get(0)) != ((int) xs.get(2)));
        final var longs = new ArrayList<Long>();
        longs.add(5000L);
        longs.add(5000L);
        System.out.println(((long) longs.get(0)) == ((long) longs.get(1)));
        System.out.println(((int) xs.getLast()) != ((int) xs.get(0)));
        System.out.println(xs.size() == longs.size() + 1);
    }
}
