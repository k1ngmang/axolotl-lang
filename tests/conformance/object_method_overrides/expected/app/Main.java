package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static final class Loc {
        public final int offset;
        public Loc(int offset) {
            this.offset = offset;
        }
        @Override public String toString() {
            return "at " + offset;
        }
        @Override public int hashCode() {
            return offset;
        }
        @Override public boolean equals(@Nullable Object other) {
            return other instanceof Loc other$1 && equals(other$1);
        }
        public boolean equals(Loc other) {
            Nulls.param(other, "other");
            return other.offset == offset;
        }
    }
    public static final class Worker extends Thread {
        public final int id;
        public Worker(int id) {
            this.id = id;
        }
        @Override public String toString() {
            return "worker " + id;
        }
        @Override public int hashCode() {
            return id;
        }
        @Override public boolean equals(
                @Nullable Object other) {
            return other instanceof Worker other$1 && other$1.id == id;
        }
    }
    public record Point(int x, int y) {
        @Override public String toString() {
            return "(" + x + ", " + y + ")";
        }
    }
    public enum Color {
        RED, GREEN;
        @Override public String toString() {
            return name().toLowerCase();
        }
    }
    public static void main(String[] args) {
        System.out.println(new Loc(1).toString() + " " + new Loc(1).equals(new Loc(1)) + " " + new Loc(2).hashCode());
        System.out.println(new Worker(3).toString() + " " + new Worker(3).equals(new Worker(3)) + " " + new Worker(4).hashCode());
        System.out.println(new Point(1, 2).toString() + " " + Color.RED);
    }
}
