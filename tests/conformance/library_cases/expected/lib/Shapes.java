package lib;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Shapes {
    private Shapes() {}
    public sealed interface Shape {}
    public record Circle(int r) implements Shape {}
    public record Square(int side) implements Shape {}
    public record Dot(int x) implements Shape {}
    public enum Level { LOW, MID, HIGH }
}
