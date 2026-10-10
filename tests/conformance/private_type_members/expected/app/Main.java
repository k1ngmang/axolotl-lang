package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static final class Patch {
        public int apply(List<String> target) {
            Nulls.param(target, "target");
            final var ctx = new Context(target);
            ctx.fuzz += 1;
            final Step step = new Twice();
            return ctx.fuzz + ctx.result.size() + new Pair(1, 2).sum() + Size.LARGE.weight + step.apply(3);
        }
    }
    @SuppressWarnings("EffectivelyPrivate") private static final class Context {
        public final List<String> result;
        public int fuzz;
        public Context(List<String> result) {
            this.result = result;
            this.fuzz = 0;
        }
    }
    @SuppressWarnings("EffectivelyPrivate") private record Pair(int a, int b) {
        public int sum() {
            return a + b;
        }
    }
    @SuppressWarnings("EffectivelyPrivate") private enum Size {
        SMALL(1), LARGE(2);
        public final int weight;
        Size(int weight) {
            this.weight = weight;
        }
    }
    @SuppressWarnings("EffectivelyPrivate") private interface Step {
        int apply(int n);
    }
    @SuppressWarnings("unused") private static final class Twice implements Step {
        @Override public int apply(int n) {
            return n * 2;
        }
    }
    public static void main(String[] args) {
        System.out.println(new Patch().apply(List.of("a", "b")));
    }
}
