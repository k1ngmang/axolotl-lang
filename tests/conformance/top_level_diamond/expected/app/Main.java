package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;

@NullMarked public final class Main {
    private Main() {}
    public static final ArrayList<Object> xs = new ArrayList<>();
    public static void main(String[] args) {
        xs.add("a");
        System.out.println(xs);
    }
}
