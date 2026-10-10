package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Node {}
    public record Leaf(String text) implements Node {
        public Leaf {
            Nulls.param(text, "text");
        }
    }
    public record Pair(Node left, Node right) implements Node {
        public Pair {
            Nulls.param(left, "left");
            Nulls.param(right, "right");
        }
    }
    public static String describe(Node node) {
        Nulls.param(node, "node");
        if (node instanceof Leaf node$1) {
            return "leaf " + node$1.text();
        }
        if (!(node instanceof Pair node$2)) {
            return "unknown";
        }
        return "pair of " + describe(node$2.left()) + " and " + describe(node$2.right());
    }
    public static boolean isEmptyLeaf(Node node) {
        Nulls.param(node, "node");
        return node instanceof Leaf node$1 && node$1.text().isEmpty();
    }
    public static boolean isNotLeaf(Node node) {
        Nulls.param(node, "node");
        return !(node instanceof Leaf node$1) || node$1.text().isEmpty();
    }
    public static int depth(Node node) {
        Nulls.param(node, "node");
        var count = 1;
        var current = node;
        while (current instanceof Pair) {
            count += 1;
            current = new Leaf("x");
        }
        return count;
    }
    public static String branches(Node node) {
        Nulls.param(node, "node");
        if (!(node instanceof Leaf node$1)) {
            return "not a leaf";
        } else if (node$1.text().isEmpty()) {
            return "empty";
        } else {
            return node$1.text();
        }
    }
    public static void main(String[] args) {
        System.out.println(describe(new Leaf("a")));
        System.out.println(describe(new Pair(new Leaf("a"), new Leaf("b"))));
        System.out.println(isEmptyLeaf(new Leaf("")));
        System.out.println(isEmptyLeaf(new Pair(new Leaf("a"), new Leaf("b"))));
        System.out.println(isNotLeaf(new Leaf("a")));
        System.out.println(depth(new Pair(new Leaf("a"), new Leaf("b"))));
        System.out.println(branches(new Leaf("")));
        System.out.println(branches(new Leaf("z")));
        System.out.println(branches(new Pair(new Leaf("a"), new Leaf("b"))));
    }
}
