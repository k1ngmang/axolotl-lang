package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var entries = List.of(Map.entry("a", 1), Map.entry("b", 2));
        System.out.println(new TreeMap<String, Integer>(Map.ofEntries(Nulls.arrayOf(entries, java.util.Map.Entry.class))));
        final var futures = List.of(CompletableFuture.completedFuture(1));
        CompletableFuture.allOf(Nulls.arrayOf(futures, CompletableFuture.class)).join();
        final var mapped = List.of(2, 3).stream().map(Nulls.asGiven(it -> CompletableFuture.completedFuture(it))).toList();
        CompletableFuture.allOf(Nulls.arrayOf(mapped, CompletableFuture.class)).join();
        System.out.println(Nulls.stream(mapped).map(Nulls.nonNull(it -> it.join())).toList());
        final var made = new ArrayList<CompletableFuture<Integer>>(mapped);
        CompletableFuture.allOf(Nulls.arrayOf(made, CompletableFuture.class)).join();
        CompletableFuture.allOf(Nulls.arrayOf(List.of(4, 5).stream().map(Nulls.nonNull(it -> CompletableFuture.completedFuture(it))).toList(), CompletableFuture.class)).join();
        System.out.println(made.size());
    }
}
