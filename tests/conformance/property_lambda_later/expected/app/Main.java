package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.function.Supplier;

@NullMarked public final class Main {
    private Main() {}
    public static final class Greeting {
        public final String name;
        public final Supplier<Integer> size;
        public final String text;
        public Greeting(String name) {
            this.name = name;
            this.size = () -> Greeting.this.text.length();
            this.text = "hello " + name;
        }
    }
    public static final class Plain {
        public final Supplier<Integer> size = () -> Plain.this.text.length();
        public final String text = "plain";
    }
    public static void main(String[] args) {
        System.out.println(new Greeting("ann").size.get());
        System.out.println(new Plain().size.get());
    }
}
