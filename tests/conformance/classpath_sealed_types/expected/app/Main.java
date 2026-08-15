package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import lib.Options;
import lib.Options.Mapper;
import lib.Options.None;
import lib.Options.Option;
import lib.Options.Some;

@NullMarked public final class Main {
    private Main() {}
    public static final class Doubler implements Mapper<Integer, Integer> {
        public Integer map(Integer x) {
            return x * 2;
        }
    }
    public static String show(Option<String> o) {
        if (o instanceof Some<String> o$1) {
            return o$1.value().toUpperCase();
        }
        return "-";
    }
    public static String leave(Option<String> o) {
        if (o instanceof None<String> o$1) {
            return "nothing";
        }
        return ((lib.Options.Some<String>) o).value().toUpperCase();
    }
    public static String describe(Option<String> o) {
        switch (o) {
            case Some<String> o$1 -> {
                return "some " + o$1.value().length();
            }
            case None<String> o$2 -> {
                return "none";
            }
        }
    }
    public static String unknownSubject() {
        final var o = Options.some("abc");
        if (o instanceof Some(var o$1, var o$2)) {
            return o$2.toUpperCase();
        }
        return "-";
    }
    public static void main(String[] args) {
        System.out.println(show(new Some<String>(true, "abc")));
        System.out.println(show(new None<String>(false)));
        System.out.println(leave(new Some<String>(true, "abc")));
        System.out.println(leave(new None<String>(false)));
        System.out.println(describe(new Some<String>(true, "abc")));
        System.out.println(unknownSubject());
        System.out.println(new Doubler().map(4));
    }
}
