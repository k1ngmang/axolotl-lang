package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        System.out.print("a");
        System.out.print(1);
        System.out.println("b");
        final var line = Console.readLine();
        System.out.println(Nulls.or(line, () -> "end of input"));
    }
}
