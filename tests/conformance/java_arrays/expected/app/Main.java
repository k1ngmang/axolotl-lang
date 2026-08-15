package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var futures = new ArrayList<CompletableFuture<Integer>>();
        futures.add(CompletableFuture.completedFuture(1));
        futures.add(CompletableFuture.supplyAsync(() -> 2));
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        System.out.println(futures.stream().map(Nulls.nonNull(it -> it.join())).toList());
        final var parts = List.of("b", "c");
        System.out.println(Path.of("a", parts.toArray(new String[0])).getNameCount());
        System.out.println(Path.of("a", parts.stream().filter(it -> it.startsWith("c")).toList().toArray(new String[0])).getFileName());
        System.out.println(String.join("-", parts));
        System.out.println(String.format("%s", parts));
        System.out.println(new ProcessBuilder(parts).command());
    }
}
