package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import app.Conflict.Clash;
import app.Deltas.EqualDelta;
import static app.Delta.size;
import static app.Deltas.equal;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final List<Delta> deltas = List.of(new InsertDelta(List.of("a")), new DeleteDelta(List.of("b", "c")),
                new Delta.ChangeDelta(List.of("d"), List.of("e", "f")), equal(List.of("g")), new Clash(List.of(), "both"));
        for (final var d : deltas) {
            System.out.println(d.lines() + " " + size(d));
        }
        System.out.println(UseFromJava.sizes());
        System.out.println(Delta.class.isSealed());
    }
}
