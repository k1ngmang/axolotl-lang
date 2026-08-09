package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static final class Worker<T> {
        public final Class<T> type;
        public Worker(Class<T> type) {
            this.type = type;
        }
        public T take(Object job) {
            return type.cast(job);
        }
        public String label(Object job) {
            return type.cast(job).toString();
        }
    }
    public static void main(String[] args) {
        final var worker = new Worker<String>(String.class);
        System.out.println(worker.take("job").length());
        System.out.println(worker.label("x"));
    }
}
