package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.IOException;
import java.io.StringReader;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

@NullMarked public final class Main {
    private Main() {}
    public static int digit(String text) {
        Nulls.param(text, "text");
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a digit: " + text, e);
        }
    }
    public static int first(String text) {
        Nulls.param(text, "text");
        final var reader = new StringReader(text);
        final int c;
        try {
            Result.mayThrow(IOException.class);
            c = reader.read();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return c;
    }
    public static int half(int n) {
        int result;
        try {
            result = check(n);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("odd " + n);
        }
        result = result / 2;
        return result;
    }
    public static int check(int n) {
        if (n < 0) {
            throw new UnsupportedOperationException("negative");
        }
        if (n % 2 != 0) {
            throw new IllegalArgumentException("odd");
        }
        return n;
    }
    public static int passed(int n) {
        try {
            return check(n);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("caught " + n);
        }
    }
    public static int interrupted() {
        Thread.currentThread().interrupt();
        final int taken;
        try {
            taken = new LinkedBlockingQueue<Integer>().take();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return taken;
    }
    public static void main(String[] args) {
        System.out.println(digit("7") + first("a") + half(8));
        report(() -> digit("x"));
        report(() -> half(3));
        report(() -> passed(3));
        report(() -> passed(-1));
        report(() -> interrupted());
        System.out.println(Thread.interrupted());
    }
    public static void report(Supplier<Object> action) {
        Nulls.param(action, "action");
        final var result = Result.attempt(() -> action.get(), e -> e.getClass().getSimpleName() + ": " + e.getMessage());
        System.out.println(result);
    }
}
