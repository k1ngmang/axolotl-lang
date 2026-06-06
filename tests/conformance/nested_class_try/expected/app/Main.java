package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<String, String> name() {
        return new Ok<>("NEW");
    }
    public static Result<String, String> run() {
        final var $t1 = name();
        if ($t1.isErr()) return Result.propagate($t1);
        final var st = Thread.State.valueOf(Result.unwrap($t1));
        final @Nullable String s = "RUNNABLE";
        final var st2 = Thread.State.valueOf(Nulls.<String>or(s, () -> "NEW"));
        System.out.println(Thread.State.valueOf(java.util.Objects.requireNonNull(s)));
        return new Ok<>(st.toString() + st2);
    }
    public static void main(String[] args) {
        System.out.println(run());
    }
}
