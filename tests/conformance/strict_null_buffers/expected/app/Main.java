package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

@NullMarked public final class Main {
    private Main() {}
    public static Character third(char[] row) {
        Nulls.param(row, "row");
        return CharBuffer.wrap(row).get(2);
    }
    public static void mark(byte[] table, int slot) {
        Nulls.param(table, "table");
        ByteBuffer.wrap(table).put(slot, Integer.valueOf(1).byteValue());
    }
    public static void main(String[] args) {
        final var row = new char[3];
        CharBuffer.wrap(row).put(2, '#');
        System.out.println(third(row));
        final var table = new byte[2];
        mark(table, 1);
        System.out.println(ByteBuffer.wrap(table).get(1));
        System.out.println(ByteBuffer.allocate(4).capacity() + ByteBuffer.allocateDirect(2).capacity() + CharBuffer.allocate(1).capacity());
        System.out.println(IntBuffer.allocate(3).capacity() + LongBuffer.allocate(2).capacity() + DoubleBuffer.allocate(1).capacity());
        System.out.println(CharBuffer.wrap("text").length());
    }
}
