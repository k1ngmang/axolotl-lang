package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> parse(String s) {
        Nulls.param(s, "s");
        return Result.attempt(() -> Integer.parseInt(s), e -> "bad " + s);
    }
    public static Result<Integer, String> total(Stream<String> numbers) {
        Nulls.param(numbers, "numbers");
        final java.util.List<Integer> $p1 = new java.util.ArrayList<>();
        for (final var it : numbers.toList()) {
            final var $t1 = parse(it);
            if ($t1.isErr()) return Result.propagate($t1);
            $p1.add(Result.<Integer>unwrap($t1));
        }
        final var parsed = $p1;
        var sum = 0;
        for (final var n : parsed) {
            sum += n;
        }
        return new Ok<>(sum);
    }
    public static final class Bag {
        public final ArrayList<String> items = new ArrayList<String>();
    }
    public static Result<List<Integer>, String> parsedAll(Bag bag) {
        Nulls.param(bag, "bag");
        final java.util.List<Integer> $p1 = new java.util.ArrayList<>();
        for (final var it : Nulls.stream(bag.items.stream()).toList()) {
            final var $t1 = parse(it);
            if ($t1.isErr()) return Result.propagate($t1);
            $p1.add(Result.<Integer>unwrap($t1));
        }
        final var numbers = $p1;
        return new Ok<>(numbers);
    }
    public static void main(String[] args) {
        final var s = Stream.of("b", "a", "c");
        System.out.println(s.sorted(java.util.Comparator.naturalOrder()).toList());
        System.out.println(Stream.of(3, 1, 2).filter(it -> it > 1).sorted(java.util.Comparator.naturalOrder()).toList());
        final var words = List.of("x", "yy", "zzz");
        System.out.println(words.stream().map(Nulls.nonNull(it -> it.length())).toList());
        System.out.println("a\nbb\nccc".lines().filter(it -> it.length() > 1).count());
        final Stream<String> typed = words.stream();
        System.out.println(Nulls.first(typed.iterator()));
        System.out.println(total(Stream.of("1", "2")));
        System.out.println(total(Stream.of("1", "x")));
        final var dir = Result.orThrow(() -> Files.createTempDirectory("axl"), e -> new IllegalStateException(e));
        final var file = dir.resolve("lines.txt");
        try {
            Files.writeString(file, "one\ntwo\nthree");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        try (var lines = Files.lines(file)) {
            System.out.println(lines.filter(it -> it.startsWith("t")).toList());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.out.println(e.getMessage());
        }
        try (var files = Files.list(dir)) {
            System.out.println(files.map(Nulls.nonNull(it -> it.getFileName().toString())).toList());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.out.println(e.getMessage());
        }
        final var bag = new Bag();
        bag.items.add("apple");
        bag.items.add("fig");
        System.out.println(Nulls.stream(bag.items.stream()).filter(it -> it.length() > 3).count());
        System.out.println(Nulls.stream(bag.items).map(Nulls.nonNull(it -> it.length())).toList());
        System.out.println(Nulls.stream(Stream.iterate("a", it -> it + "a")).limit(3).toList());
        System.out.println(parsedAll(bag));
    }
}
