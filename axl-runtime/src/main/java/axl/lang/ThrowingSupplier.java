package axl.lang;
@FunctionalInterface public interface ThrowingSupplier<T> { T get() throws Exception; }
