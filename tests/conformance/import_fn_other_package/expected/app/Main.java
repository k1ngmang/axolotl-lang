package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import static util.Strings.shout;
import static util.Strings.limit;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        System.out.println(shout("hi"));
        System.out.println(limit);
    }
}
