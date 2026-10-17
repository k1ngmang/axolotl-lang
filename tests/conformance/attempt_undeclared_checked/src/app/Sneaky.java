package app;

/** Rethrows a checked exception undeclared, as Axolotl code that wraps Java I/O does. */
public final class Sneaky {
    private Sneaky() {
    }

    public static RuntimeException sneaky(final Throwable t) {
        throw Sneaky.<RuntimeException>doThrow(t);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E doThrow(final Throwable t) throws E {
        throw (E) t;
    }
}
