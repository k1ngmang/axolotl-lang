package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.time.LocalDate;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var parsed = Result.attempt(() -> LocalDate.parse("2026-13-40"), e -> "bad date");
        System.out.println(parsed);
    }
}
