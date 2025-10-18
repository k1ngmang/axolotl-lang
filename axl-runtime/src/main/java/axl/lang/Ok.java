package axl.lang;
public record Ok<T, E>(T value) implements Result<T, E> {}
