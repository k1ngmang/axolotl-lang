package app;

import java.util.List;

public final class JavaMain {
    public static void main(String[] args) {
        System.out.println(Chunk.last(new Chunk(3, List.of("a", "b"), null)));
        System.out.println(new Patch("p").applyTo(List.of("x"), 2, null));
        Object first = Chunk.first(List.of(), null);
        System.out.println(first);
        System.out.println(new Chunk.Box<String>(null, "empty").label());
        System.out.println(Chunk.joined("a", null, 1L, true, 0.5));
        expectNull(() -> new Chunk(3, null, null));
        expectNull(() -> new Patch(null, List.of(), null, 0));
        expectNull(() -> new Patch((String) null));
        expectNull(() -> new Patch(null, "label"));
        expectNull(() -> new Patch("p").applyTo(null, 1, null));
        expectNull(() -> new Chunk(1, List.of(), null).shifted(1, null));
        expectNull(() -> Chunk.last(null));
        expectNull(() -> Chunk.largest("a", null));
        expectNull(() -> new Chunk.Box<String>("v", null));
        expectNull(() -> new Chunk.Ranked<String>(null));
        expectNull(() -> new Patch.Failure(null));
        expectNull(() -> new Patch.Wrapper(null));
    }

    private static void expectNull(Runnable call) {
        try {
            call.run();
            System.out.println("no exception");
        } catch (NullPointerException e) {
            StackTraceElement top = e.getStackTrace()[0];
            System.out.println(e.getMessage() + ", at " + top.getClassName() + "." + top.getMethodName());
        }
    }
}
