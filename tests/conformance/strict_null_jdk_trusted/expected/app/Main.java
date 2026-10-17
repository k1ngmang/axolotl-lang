package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@NullMarked public final class Main {
    private Main() {}
    public enum Level { LOW, HIGH }
    public static String levels(SortedMap<String, Integer> counts) {
        Nulls.param(counts, "counts");
        final var names = new ArrayList<String>();
        for (final var entry : counts.entrySet()) {
            names.add(entry.getKey() + "=" + entry.getValue());
        }
        for (final var key : counts.keySet()) {
            names.add(key.toUpperCase());
        }
        final var total = Nulls.stream(counts.values()).map(Nulls.asGiven(it -> it + 0)).count();
        return String.join(",", names) + " " + total + " " + counts.headMap("m").size();
    }
    public static void main(String[] args) {
        final var counts = new TreeMap<String, Integer>();
        counts.put("a", 1);
        counts.put("z", 2);
        System.out.println(levels(counts));
        final var words = List.of("x", "y", "z");
        System.out.println(String.join("-", words.subList(1, 3)));
        System.out.println(words.iterator().next().length());
        System.out.println(Level.HIGH.name().toLowerCase() + Level.valueOf("LOW").name().length());
        System.out.println(words.getClass().getSimpleName().length() > 0);
        System.out.println(Level.HIGH.getDeclaringClass().getSimpleName());
        final var file = Result.orThrow(() -> Files.createTempFile("axl", ".txt"), e -> new IllegalStateException(e));
        final var written = Result.orThrow(() -> Files.writeString(file, "one\ntwo\n"), e -> new IllegalStateException(e));
        final var lines = Result.orThrow(() -> Files.readAllLines(written), e -> new IllegalStateException(e));
        final var text = Result.orThrow(() -> Files.readString(file), e -> new IllegalStateException(e));
        System.out.println(lines.get(1).length() + text.strip().length());
        final var $t1 = Nulls.map(file.toAbsolutePath().normalize().getFileName(), $n1 -> Nulls.map($n1.toString(), $n2 -> $n2.endsWith(".txt")));
        System.out.println($t1);
        final var deleted = Result.orThrow(() -> Files.deleteIfExists(file), e -> new IllegalStateException(e));
        System.out.println(deleted);
        final var date = LocalDate.of(2026, 10, 20);
        System.out.println(date.plusDays(30).getDayOfWeek().name());
        System.out.println(Optional.ofNullable(System.getProperty("axl.none")).orElse("v").length() + Optional.ofNullable(counts.get("missing")).orElse(7));
        final var sb = new StringBuilder();
        sb.append("a").append(1);
        System.out.println(sb.reverse().toString());
        final var matcher = Pattern.compile("(\\d+)").matcher("n=42");
        if (matcher.find()) {
            System.out.println(Nulls.or(matcher.group(1), () -> "none"));
        }
        System.out.println(words.stream().collect(Collectors.joining("+")).length());
        System.out.println(Map.entry("k", 3).getValue() + Map.of("k", 4).entrySet().size());
        System.out.println(Thread.currentThread().getName().isEmpty());
    }
}
