package lib;

import axl.lang.Result;
import java.util.List;

// Java code without nullness annotations.
public interface Selector {
    Result<Void, String> select(String name);

    int count(List<String> names);

    static String run(Selector selector, List<String> names) {
        return selector.select(names.get(0)) + " " + selector.count(names);
    }
}
