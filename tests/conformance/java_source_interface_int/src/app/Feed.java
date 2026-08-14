package app;

public interface Feed<T> {

    T next();

    void push(T item);
}
