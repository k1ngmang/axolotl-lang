package zz;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Cfg {
    private Cfg() {}
    public static final List<String> names = List.of("a", "b");
    public static final int count = names.size();
}
