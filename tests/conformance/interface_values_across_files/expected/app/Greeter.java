package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public interface Greeter {
    String name();
    String greet(String who);
}
