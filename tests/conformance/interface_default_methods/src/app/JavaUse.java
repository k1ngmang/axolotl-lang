package app;

import java.util.List;

public class JavaUse {
    public static Handler<Object> silent() {
        return new Handler<Object>() {
            @Override
            public void value(String text) {
            }
        };
    }

    public static String run() {
        Main.Named named = new Main.Person("java", List.of());
        Handler<String> plain = new Handler<>() {};
        return named.greeting() + " " + named.shout() + " " + plain.startArray();
    }
}
