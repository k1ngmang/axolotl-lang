package lib;

public sealed interface Option<T> {
    record Some<T>(T value) implements Option<T> {}

    record None<T>() implements Option<T> {}
}
