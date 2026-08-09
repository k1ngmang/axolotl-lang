package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Boolean, String> ok(int x) {
        return new Ok<>(x > 1);
    }
    public static Result<Integer, String> count() {
        var n = 0;
        loop$1: for (final var x : List.of(1, 2, 3)) {
            do {
                if (!(x > 2)) {
                    final var $t1 = ok(x);
                    if ($t1.isErr()) return Result.propagate($t1);
                    if (!(Result.unwrap($t1))) {
                        break loop$1;
                    }
                }
                n += 1;
            } while (false);
        }
        return new Ok<>(n);
    }
    public static Result<Integer, String> skipped() {
        var n = 0;
        var i = 0;
        loop$1: while (i < 4) {
            i += 1;
            do {
                if (i != 2) {
                    final var $t1 = ok(i);
                    if ($t1.isErr()) return Result.propagate($t1);
                    if (Result.unwrap($t1)) {
                        n += i;
                        break;
                    }
                }
                continue loop$1;
            } while (false);
            n += 10;
        }
        return new Ok<>(n);
    }
    public static void main(String[] args) {
        System.out.println(count());
        System.out.println(skipped());
    }
}
