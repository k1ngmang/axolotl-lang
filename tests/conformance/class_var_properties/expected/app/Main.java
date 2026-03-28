package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Node {}
    public record Leaf(String text) implements Node {}
    public record Pair(Node left, Node right) implements Node {}
    public static final class Holder {
        public Node node;
        public int count;
        public Holder(Node node) {
            this.node = node;
            this.count = 0;
        }
        public void swap() {
            if (node instanceof Leaf) {
                node = new Leaf("new");
            }
        }
        public Result<Integer, String> bump() {
            count += 10;
            return new Ok<>(1);
        }
        public Result<Integer, String> add() {
            final var $t1 = count;
            final var $t2 = bump();
            if ($t2.isErr()) return Result.propagate($t2);
            count = $t1;
            count += Result.unwrap($t2);
            return new Ok<>(count);
        }
        public String describe() {
            final var current = node;
            switch (current) {
                case Leaf current$1 -> {
                    return current$1.text();
                }
                case Pair current$2 -> {
                    return "pair";
                }
            }
        }
    }
    public static void main(String[] args) {
        final var h = new Holder(new Leaf("old"));
        h.swap();
        System.out.println(h.describe());
        System.out.println(h.add());
    }
}
