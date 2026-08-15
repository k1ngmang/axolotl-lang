package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@NullMarked public final class Main {
    private Main() {}
    public static final class Worker implements Runnable {
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(20L);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    System.out.print("");
                }
            }
        }
    }
    public static boolean interrupted() {
        return Thread.interrupted();
    }
    public static boolean statement() {
        Thread.currentThread().interrupt();
        try {
            Thread.sleep(10000L);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.out.print("");
        }
        return interrupted();
    }
    public static boolean value() {
        Thread.currentThread().interrupt();
        final var taken = Result.attempt(() -> new LinkedBlockingQueue<Integer>().take(), e -> -1);
        return taken instanceof Err(var taken$1) && interrupted();
    }
    public static int waitLong() {
        try {
            Thread.sleep(10000L);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("woken");
        }
        return 1;
    }
    public static boolean rethrown() {
        Thread.currentThread().interrupt();
        final var slept = Result.attempt(() -> waitLong(), e -> e.getMessage());
        return slept instanceof Err(var slept$1) && interrupted();
    }
    public static int take() {
        final var taken = Result.orThrow(() -> new LinkedBlockingQueue<Integer>().take(), e -> new IllegalStateException(e));
        return taken;
    }
    public static boolean thrown() {
        Thread.currentThread().interrupt();
        final var taken = Result.attempt(() -> take(), e -> e.getMessage());
        return taken instanceof Err(var taken$1) && interrupted();
    }
    public static boolean exit() {
        var slept = 0;
        for (final var i : List.of(1, 2)) {
            Thread.currentThread().interrupt();
            try {
                Thread.sleep(10000L);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                continue;
            }
            slept += 1;
        }
        return slept == 0 && interrupted();
    }
    public static boolean resource() {
        Thread.currentThread().interrupt();
        try (var reader = new StringReader("x")) {
            Thread.sleep(10000L);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.out.print("");
        }
        return interrupted();
    }
    public static void main(String[] args) {
        System.out.println("statement " + statement());
        System.out.println("value " + value());
        System.out.println("rethrown " + rethrown());
        System.out.println("thrown " + thrown());
        System.out.println("exit " + exit());
        System.out.println("resource " + resource());
        final var pool = Executors.newSingleThreadExecutor();
        pool.execute(new Worker());
        try {
            Thread.sleep(50L);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        pool.shutdownNow();
        final var stopped = Result.attempt(() -> pool.awaitTermination(5L, TimeUnit.SECONDS), e -> false);
        System.out.println("worker stopped " + stopped.getOrElse(false));
    }
}
