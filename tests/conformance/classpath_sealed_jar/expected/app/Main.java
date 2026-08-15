package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;
import lib.Options.Mapper;
import lib.Options.Option;
import lib.Options.Some;

@NullMarked public final class Main {
    private Main() {}
    public static final class Length implements Mapper<String, Integer> {
        public Integer map(String x) {
            return x.length();
        }
    }
    public static int lengths(List<Option<String>> options) {
        var total = 0;
        for (final var o : options) {
            if (o instanceof Some(var o$1, var o$2)) {
                total += new Length().map(o$2);
            }
        }
        return total;
    }
    public static void main(String[] args) {
        System.out.println(lengths(List.of(new Some<String>(true, "ab"), new Some<String>(true, "cde"))));
    }
}
