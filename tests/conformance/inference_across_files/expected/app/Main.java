package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static final int n = zz.Cfg.count + 1;
    public static final int m = n + zz.Cfg.names.size();
    public static void main(String[] args) {
        System.out.println(m);
    }
}
