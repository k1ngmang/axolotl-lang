package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static final class Fixed implements Source {
        @Override public String name() {
            return "fixed";
        }
    }
    public static final class Generator {
        private final int columnWidth;
        private final List<String> tags;
        private final String separator;
        private final long boxed;
        private final String held;
        private final String label;
        private final String sourceName;
        private final @Nullable Integer spareWidth;
        public Generator(Options options, Box<Long> box, Box.Holder<String> holder, Source source, @Nullable Options spare) {
            Nulls.param(options, "options");
            Nulls.param(box, "box");
            Nulls.param(holder, "holder");
            Nulls.param(source, "source");
            this.columnWidth = options.columnWidth;
            this.tags = options.tags;
            this.separator = options.separator(true);
            this.boxed = box.value();
            this.held = holder.get();
            this.label = box.label();
            this.sourceName = source.name();
            this.spareWidth = Nulls.map(spare, $n1 -> $n1.columnWidth);
        }
        public String describe() {
            return "" + columnWidth + separator + tags.size() + separator + (boxed + 1L) +
                    separator +
                    label.length() +
                    held.length() +
                    separator +
                    sourceName.length() +
                    separator +
                    Nulls.or(spareWidth, -1);
        }
    }
    public static void main(String[] args) {
        final var options = new Options();
        options.columnWidth = 100;
        System.out.println(new Generator(options, new Box<Long>(41L, "box"), new Box.Holder<String>("held"), new Fixed(), null).describe());
        System.out.println(new Generator(options, new Box<Long>(1L, ""), new Box.Holder<String>(""), new Fixed(), options).describe());
    }
}
