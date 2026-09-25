package lib;

import axl.lang.Result;
import java.util.List;
import org.jspecify.annotations.NullMarked;

// Null-marked Java code: its type arguments are not null unless they say `@Nullable`.
@NullMarked
public interface Selector {
    Result<Void, String> select(String name);

    int count(List<String> names);
}
