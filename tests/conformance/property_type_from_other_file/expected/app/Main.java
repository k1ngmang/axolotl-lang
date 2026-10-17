package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import app.Model.Missing;
import app.Model.Some;

@NullMarked public final class Main {
    private Main() {}
    public static final class Holder {
        public final Row row;
        public final java.util.List<String> tags;
        public final java.util.Map<String, java.util.List<Integer>> counts;
        public final java.util.List<String> first;
        public final java.util.List<String> names;
        public final app.Names.Tag tag;
        public final app.Model.Option<String> found;
        public Holder(Row row) {
            Nulls.param(row, "row");
            this.row = row;
            this.tags = row.tags();
            this.counts = row.counts();
            this.first = row.firstTags(1);
            this.names = Names.all();
            this.tag = Names.tag();
            this.found = Names.find(1);
        }
        public String describe() {
            return String.valueOf(tags.size()) + " " + counts.get("k") + " " + first + " " + names + " " + tag.label();
        }
        public String foundName() {
            switch (found) {
                case Some<String> found$1 -> {
                    return found$1.value();
                }
                case Missing<String> found$2 -> {
                    return found$2.reason();
                }
            }
        }
    }
    public static void main(String[] args) {
        final var holder = new Holder(new Row(java.util.List.of("a", "b"), java.util.Map.of("k", java.util.List.of(1, 2))));
        System.out.println(holder.describe());
        System.out.println(holder.foundName());
        System.out.println(Names.find(5) instanceof Some<?>);
    }
}
