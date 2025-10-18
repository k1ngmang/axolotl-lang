package app;

import axl.lang.*;

public final class JavaMain {
    public static void main(String[] args) {
        System.out.println(describe(util.Parse.doubled("21")));
        System.out.println(describe(util.Parse.doubled("x")));
    }

    static String describe(Result<Integer, String> r) {
        return switch (r) {
            case Ok<Integer, String>(var v) -> "ok " + v;
            case Err<Integer, String>(var e) -> "fail " + e;
        };
    }
}
