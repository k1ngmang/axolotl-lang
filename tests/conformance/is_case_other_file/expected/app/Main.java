package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import app.Model.Some;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        System.out.println((Rules.validate(new Model.Order(0)) instanceof Err<?, ?> ? "bad" : "good"));
        System.out.println(!(Rules.validate(new Model.Order(1)) instanceof Ok<?, ?>));
        System.out.println(Rules.find(2) instanceof Some<?>);
    }
}
