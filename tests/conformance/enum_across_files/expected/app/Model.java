package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Model {
    private Model() {}
    public enum Level { LOW, MID, HIGH }
    public sealed interface Shape {}
    public record Circle(double radius) implements Shape {}
    public record Square(double side) implements Shape {}
}
