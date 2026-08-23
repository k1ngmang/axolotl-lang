package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var futures = new ArrayList<CompletableFuture<Integer>>();
        futures.add(CompletableFuture.completedFuture(1));
        futures.add(CompletableFuture.supplyAsync(() -> 2));
        CompletableFuture.allOf(Nulls.arrayOf(futures, CompletableFuture.class)).join();
        System.out.println(futures.stream().map(Nulls.nonNull(it -> it.join())).toList());
        final var parts = List.of("b", "c");
        System.out.println(Path.of("a", parts.toArray(new String[0])).getNameCount());
        System.out.println(Path.of("a", parts.stream().filter(it -> it.startsWith("c")).toList().toArray(new String[0])).getFileName());
        System.out.println(String.join("-", parts));
        System.out.println(String.format("%s", parts));
        System.out.println(new ProcessBuilder(parts).command());
        final List<Map.Entry<String, Integer>> entries = List.of(Map.entry("a", 1), Map.entry("b", 2));
        final Map<String, Integer> fromVal = Map.ofEntries(Nulls.arrayOf(entries, java.util.Map.Entry.class));
        final Map<String, Integer> inline = Map.ofEntries(Nulls.arrayOf(List.of(Map.entry("c", 3)), java.util.Map.Entry.class));
        System.out.println(fromVal.get("a") + " " + fromVal.get("b") + " " + inline.get("c"));
        System.out.println(Path.of("x", List.of("y", "z").toArray(new String[0])).getNameCount());
    }
}
