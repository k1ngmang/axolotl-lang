package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Problem {}
    public record BadDate(String text) implements Problem {
        public BadDate {
            Nulls.param(text, "text");
        }
    }
    public record TooEarly(LocalDate date) implements Problem {
        public TooEarly {
            Nulls.param(date, "date");
        }
    }
    public static Result<LocalDate, BadDate> parseDate(String text) {
        Nulls.param(text, "text");
        return Result.attempt(() -> LocalDate.parse(text), e -> new BadDate(text));
    }
    public static Result<LocalDate, Problem> checked(String text) {
        Nulls.param(text, "text");
        final var $t1 = parseDate(text);
        if ($t1.isErr()) return Result.propagate($t1);
        final var date = Result.unwrap($t1);
        return (date.getYear() < 2000 ? new Err<>(new TooEarly(date)) : new Ok<>(date));
    }
    public static Result<Integer, String> number(String text) {
        Nulls.param(text, "text");
        return Result.attempt(() -> Integer.parseInt(text), e -> "not a number: " + text);
    }
    public static Result<Integer, Problem> mapped(String text) {
        Nulls.param(text, "text");
        final var $t1 = number(text).mapErr(m -> new BadDate(m));
        if ($t1.isErr()) return Result.propagate($t1);
        final var n = Result.unwrap($t1);
        return new Ok<>(n);
    }
    public static Result<Integer, Problem> attempted(String text) {
        Nulls.param(text, "text");
        final var $t1 = Result.attempt(() -> Integer.parseInt(text), e -> new BadDate(text));
        if ($t1.isErr()) return Result.propagate($t1);
        final var n = Result.unwrap($t1);
        return new Ok<>(n);
    }
    public static Result<List<LocalDate>, Problem> dates(List<String> texts) {
        Nulls.param(texts, "texts");
        final java.util.List<LocalDate> $p1 = new java.util.ArrayList<>();
        for (final var it : texts) {
            final var $t1 = parseDate(it);
            if ($t1.isErr()) return Result.propagate($t1);
            $p1.add(Result.<LocalDate>unwrap($t1));
        }
        final var parsed = $p1;
        return new Ok<>(parsed);
    }
    public static Result<Integer, Object> widened(String text) {
        Nulls.param(text, "text");
        final var $t1 = number(text);
        if ($t1.isErr()) return Result.propagate($t1);
        final var n = Result.unwrap($t1);
        return new Ok<>(n);
    }
    public static Result<String, FileNotFoundException> open(String name) {
        Nulls.param(name, "name");
        return (name.isEmpty() ? new Err<>(new FileNotFoundException("no name")) : new Ok<>(name));
    }
    public static Result<Integer, IOException> read(String name) {
        Nulls.param(name, "name");
        final var $t1 = open(name);
        if ($t1.isErr()) return Result.propagate($t1);
        final var found = Result.unwrap($t1);
        return new Ok<>(found.length());
    }
    public static void main(String[] args) {
        System.out.println(checked("1999-01-02"));
        System.out.println(checked("x"));
        System.out.println(checked("2026-10-21"));
        System.out.println(mapped("7").toString() + " " + mapped("x"));
        System.out.println(attempted("8").toString() + " " + attempted("y"));
        System.out.println(dates(List.of("2026-01-01", "z")));
        System.out.println(widened("9").toString() + " " + widened("w"));
        System.out.println(read("abc").toString() + " " + read("").isErr());
    }
}
