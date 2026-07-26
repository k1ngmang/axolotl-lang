package axl.lang;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** `readLine()`: the standard input, one line at a time. */
@NullMarked
public final class Console {

    // One reader for the whole program: a new one per call would swallow buffered input.
    private static final BufferedReader IN = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    private Console() {}

    /** The next line of the standard input without its line end, or null at its end. */
    public static @Nullable String readLine() {
        try {
            return IN.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
