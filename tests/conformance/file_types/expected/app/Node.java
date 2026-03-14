package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public sealed interface Node {
    String label();
    public record Leaf(String label) implements Node {}
    public record Branch(String label, java.util.List<Node> children) implements Node {}
    public static final Node root = new Branch("root", java.util.List.of(new Leaf("a"), new Leaf("b")));
    public static int count(Node node) {
        switch (node) {
            case Leaf $s1 -> {
                return 1;
            }
            case Branch $s2 -> {
                var total = 1;
                for (final var child : $s2.children()) {
                    total += count(child);
                }
                return total;
            }
        }
    }
}
