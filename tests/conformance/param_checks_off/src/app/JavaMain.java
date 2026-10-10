package app;

public final class JavaMain {
    public static void main(String[] args) {
        Chunk chunk = new Chunk(3, null);
        Chunk.Patch patch = new Chunk.Patch(null);
        System.out.println("built " + chunk + " and " + patch.name);
        System.out.println(patch.describe(null));
        try {
            Chunk.last(chunk);
        } catch (NullPointerException e) {
            System.out.println("failed later, in " + e.getStackTrace()[0].getMethodName());
        }
    }
}
