package app;

/** A Java interface without nullness annotations whose result is a type variable. */
@FunctionalInterface
public interface Conv<T> {
    T apply(String s) throws Exception;
}
