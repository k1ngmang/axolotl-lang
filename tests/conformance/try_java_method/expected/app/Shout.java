package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import lib.Lookup;

@NullMarked public final class Shout {
    private Shout() {}
    public static Result<String, String> shout(String key) {
        final var $t1 = Lookup.find(key);
        if ($t1.isErr()) return Result.propagate($t1);
        final var value = Result.unwrap($t1);
        return new Ok<>(value + "!");
    }
    public static void main(String[] args) {
        System.out.println(shout("hey"));
        System.out.println(shout(""));
    }
}
