package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import app.Node.Branch;
import app.Node.Leaf;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        System.out.println(Node.count(Node.root));
        System.out.println(Point.add(new Point(1, 2), Point.origin));
        System.out.println(new Leaf("x").label());
        System.out.println(new Branch("b", java.util.List.of()).children());
    }
}
