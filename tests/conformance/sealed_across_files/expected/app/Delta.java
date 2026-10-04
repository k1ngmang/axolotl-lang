package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import app.Deltas.EqualDelta;
import java.util.List;

@NullMarked public sealed interface Delta permits Delta.ChangeDelta, Conflict, DeleteDelta, Deltas.EqualDelta, InsertDelta {
    List<String> lines();
    public record ChangeDelta(List<String> lines, List<String> replaced) implements Delta {}
    public static int size(Delta d) {
        switch (d) {
            case InsertDelta d$1 -> {
                return d$1.lines().size();
            }
            case DeleteDelta d$2 -> {
                return -d$2.lines().size();
            }
            case ChangeDelta d$3 -> {
                return d$3.lines().size() - d$3.replaced().size();
            }
            case EqualDelta d$4 -> {
                return 0;
            }
            case Conflict d$5 -> {
                return 100;
            }
        }
    }
}
