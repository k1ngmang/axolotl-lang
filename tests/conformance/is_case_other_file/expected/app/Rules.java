package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import app.Model.Missing;
import app.Model.Option;
import app.Model.Order;
import app.Model.Some;

@NullMarked public final class Rules {
    private Rules() {}
    public static Result<Order, String> validate(Order o) {
        return (o.id() > 0 ? new Ok<>(o) : new Err<>("bad"));
    }
    public static Option<Order> find(int id) {
        return (id > 0 ? new Some<>(new Order(id)) : new Missing<>("none"));
    }
}
