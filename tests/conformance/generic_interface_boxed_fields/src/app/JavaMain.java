package app;

public final class JavaMain {
    public static void main(String[] args) {
        Keys.main(args);
        Keys.Entry entry = new Keys.Entry(1000, "java");
        int key = entry.key();
        System.out.println(key + 1);
        Keys.Keyed<Integer> keyed = entry;
        System.out.println(keyed.key().equals(new Keys.Entry(1000, "other").key()));
        Keys.Named<Long> account = new Keys.Account(7L, "bob");
        long id = account.key();
        System.out.println(id + new Keys.Tagged(1L).key());
        System.out.println(new Keys.Range(1, 4).high());
        System.out.println(Keys.describe(entry));
        System.out.println(Compare.sameKeys(entry, new Keys.Entry(1000, "copy")));
        System.out.println(Compare.wide(new Keys.Range(1000, 1000)));
        try {
            new Keys.Entry(null, "none");
            System.out.println("no exception");
        } catch (NullPointerException e) {
            System.out.println(e.getMessage());
        }
    }
}
