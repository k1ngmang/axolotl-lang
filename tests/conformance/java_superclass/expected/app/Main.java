package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.LinkedHashMap;
import java.util.Map;

@NullMarked public final class Main {
    private Main() {}
    public static final class NotFound extends RuntimeException {
        public final String id;
        public NotFound(String id) {
            super("not found: " + id);
            Nulls.param(id, "id");
            this.id = id;
        }
    }
    public static final class Loud extends Thread {
        public final int times;
        public int shouted;
        public Loud(int times) {
            super("loud");
            this.times = times;
            this.shouted = 0;
        }
        public void run() {
            var i = 0;
            while (i < times) {
                shouted += 1;
                i += 1;
            }
        }
    }
    public static final class Recent extends LinkedHashMap<String, Integer> {
        public final int limit;
        public Recent(int limit) {
            this.limit = limit;
        }
        public boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            Nulls.param(eldest, "eldest");
            return size() > limit;
        }
    }
    public static final class Plain extends Object {
        @Override public String toString() {
            return "plain";
        }
    }
    public static String find(String id) {
        Nulls.param(id, "id");
        if (id.isEmpty()) {
            throw new NotFound(id);
        }
        return "user " + id;
    }
    public static void main(String[] args) {
        System.out.println(find("7"));
        System.out.println(Result.attempt(() -> find(""), e -> e.getMessage()));
        final var loud = new Loud(3);
        loud.start();
        try {
            loud.join();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        System.out.println(loud.shouted + " " + loud.getName());
        final var recent = new Recent(2);
        recent.put("a", 1);
        recent.put("b", 2);
        recent.put("c", 3);
        System.out.println(recent.keySet());
        System.out.println(new Plain());
    }
}
