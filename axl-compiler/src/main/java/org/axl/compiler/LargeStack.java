package org.axl.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** The parser and the translator recurse once per nesting level of an expression. */
public final class LargeStack {

    private static final long STACK_SIZE = 512L * 1024 * 1024;

    private LargeStack() {}

    /** Runs the work on a thread with a large stack; tooDeep gives the result when even that overflows. */
    public static <T> T call(Supplier<T> work, Supplier<T> tooDeep) {
        List<T> result = new ArrayList<>();
        List<RuntimeException> failure = new ArrayList<>();
        Thread thread = new Thread(null, () -> {
            try {
                result.add(work.get());
            } catch (StackOverflowError e) {
                result.add(tooDeep.get());
            } catch (RuntimeException e) {
                failure.add(e);
            }
        }, "axl-translate", STACK_SIZE);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while translating", e);
        }
        if (!failure.isEmpty()) {
            throw failure.getFirst();
        }
        return result.getFirst();
    }
}
