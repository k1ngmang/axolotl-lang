package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@NullMarked public final class Main {
    private Main() {}
    public static final class Locked implements AutoCloseable {
        private final Lock lock;
        public Locked(Lock lock) {
            Nulls.param(lock, "lock");
            this.lock = lock;
        }
        public void close() {
            lock.unlock();
            System.out.println("unlocked");
        }
    }
    public static Locked hold(Lock lock) {
        Nulls.param(lock, "lock");
        lock.lock();
        return new Locked(lock);
    }
    public static final class Printer {
        private final ReentrantLock lock = new ReentrantLock();
        private final StringBuilder out = new StringBuilder();
        public void print(String value) {
            Nulls.param(value, "value");
            try (var $r1 = hold(lock)) {
                out.append(value);
            }
        }
        public String text() {
            return out.toString();
        }
    }
    public static int use(int x) {
        return x + 1;
    }
    public static void main(String[] args) {
        final var p = new Printer();
        p.print("a");
        System.out.println(p.text());
        System.out.println(use(1));
        try (var $r1 = hold(new ReentrantLock())) {
            throw new IllegalStateException("boom");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.out.println("caught " + e.getMessage());
        }
        try (var $r2 = new BufferedReader(new StringReader("x"))) {
            Result.mayThrow(java.io.IOException.class);
            System.out.println("reading");
        } catch (java.io.IOException e) {
            System.out.println(e);
        }
    }
}
