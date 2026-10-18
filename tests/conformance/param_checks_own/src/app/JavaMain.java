package app;

public final class JavaMain {
    public static void main(String[] args) {
        System.out.println(new Obj.Str("s", "!").get() + " " + Obj.wrapped("w") + " " + Imported.shout("i"));
        expectNull(() -> new Obj().add(null, "v", "l"));
        expectNull(() -> new Obj().add("n", null, "l"));
        expectNull(() -> new Obj().add("n", "v", null));
        expectNull(() -> new Obj().set(null, 1));
        expectNull(() -> Obj.wrapped(null));
        expectNull(() -> new Obj.Str(null, "!"));
        expectNull(() -> new Obj.Str("s", null));
        expectNull(() -> Imported.shout(null));
    }

    private static void expectNull(Runnable call) {
        try {
            call.run();
            System.out.println("no exception");
        } catch (NullPointerException e) {
            System.out.println(e.getMessage());
        }
    }
}
