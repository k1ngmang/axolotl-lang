package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;
import java.util.Map;

@NullMarked public record Row(List<String> tags, Map<String, List<Integer>> counts) {
    public Row {
        Nulls.param(tags, "tags");
        Nulls.param(counts, "counts");
    }
    public List<String> firstTags(int n) {
        return tags.subList(0, n);
    }
}
