package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static final class Connection {
        public final List<String> log = new ArrayList<String>();
        public boolean open = true;
        public void commit() {
            if (!open) {
                throw new IllegalStateException("closed");
            }
            log.add("commit");
        }
        public void close() {
            open = false;
            log.add("close");
        }
    }
    public static void finish(Connection c) {
        try {
            c.commit();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.out.println("commit failed: " + e.getMessage());
        }
        try {
            c.close();
        } catch (Exception $ex1) {
            if ($ex1 instanceof InterruptedException) Thread.currentThread().interrupt();
            System.out.println("close failed");
        }
    }
    public static void mustCommit(Connection c) {
        try {
            c.commit();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("cannot commit", e);
        }
    }
    public static void pause() {
        try {
            Thread.sleep(1L);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        System.out.println("rested");
    }
    public static void main(String[] args) {
        final var c = new Connection();
        finish(c);
        finish(c);
        System.out.println(c.log);
        pause();
        final var broken = new Connection();
        broken.close();
        try {
            mustCommit(broken);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.out.println("main: " + e.getMessage());
        }
    }
}
