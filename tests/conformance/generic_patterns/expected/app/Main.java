package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import app.Options.None;
import app.Options.Option;
import app.Options.Some;

@NullMarked public final class Main {
    private Main() {}
    public static String show(String s) {
        final var parsed = Options.parse(s);
        if (parsed instanceof Some<Integer> parsed$1) {
            return "some " + (parsed$1.value() + 1);
        }
        return "none " + parsed.present();
    }
    public static String describe(String s) {
        final var parsed = Options.parse(s);
        switch (parsed) {
            case Some<Integer> parsed$1 -> {
                return "value " + parsed$1.value().toString().length();
            }
            case None<Integer> parsed$2 -> {
                return "nothing";
            }
        }
    }
    public static String known(Option<String> option) {
        switch (option) {
            case Some<String> option$1 -> {
                return option$1.value().toUpperCase();
            }
            case None<String> option$2 -> {
                return "-";
            }
        }
    }
    public static void main(String[] args) {
        System.out.println(show("41"));
        System.out.println(show("x"));
        System.out.println(describe("123"));
        System.out.println(describe("y"));
        System.out.println(known(new Some<String>(true, "abc")));
        System.out.println(known(new None<String>(false)));
    }
}
