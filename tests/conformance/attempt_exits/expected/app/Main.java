package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static void send(int i) {
        if (i % 2 == 0) {
            throw new IllegalStateException("even " + i);
        }
    }
    public static void parse(String s) {
        Nulls.param(s, "s");
        Integer.parseInt(s);
    }
    public static Result<Boolean, String> small(int i) {
        return new Ok<>(i < 4);
    }
    public static void loops() {
        for (final var i : List.of(1, 2, 3, 4, 5)) {
            try {
                send(i);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                continue;
            }
            System.out.println("sent " + i);
        }
        for (final var i : List.of(1, 2, 3)) {
            try {
                send(i);
            } catch (Exception $ex1) {
                if ($ex1 instanceof InterruptedException) Thread.currentThread().interrupt();
                break;
            }
            System.out.println("sent " + i);
        }
        var n = 0;
        while (n < 6) {
            n += 1;
            final var $c1 = n;
            try {
                send($c1);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                continue;
            }
            System.out.print(n + " ");
        }
        System.out.println();
    }
    public static int first(int i) {
        try {
            send(i);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return -1;
        }
        return 1;
    }
    public static String describe(String s) {
        Nulls.param(s, "s");
        try {
            parse(s);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "bad: " + e.getMessage();
        }
        return "number";
    }
    public static void quiet(int i) {
        try {
            send(i);
        } catch (Exception $ex1) {
            if ($ex1 instanceof InterruptedException) Thread.currentThread().interrupt();
            return;
        }
        System.out.println("quiet " + i);
    }
    public static void nested() {
        final var seen = new ArrayList<String>();
        for (final var row : List.of(1, 3)) {
            for (final var col : List.of(1, 2, 3)) {
                try {
                    send(col);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    break;
                }
                seen.add(row + ":" + col);
            }
            try {
                send(row + 1);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                continue;
            }
            seen.add("row " + row);
        }
        System.out.println(seen);
    }
    public static void inWhen() {
        loop$1: for (final var i : List.of(1, 2, 3)) {
            switch (Nulls.box(i)) {
                case 1 -> {
                    try {
                        send(i);
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        break loop$1;
                    }
                    System.out.println("one");
                }
                case null, default -> {
                    try {
                        send(i);
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        continue;
                    }
                    System.out.println("other " + i);
                }
            }
        }
        loop$2: for (final var i : List.of(1, 2, 3, 4)) {
            switch (Nulls.box(i)) {
                case 2 -> {
                    try {
                        send(i);
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        break loop$2;
                    }
                }
                case null, default -> {
                    System.out.println("saw " + i);
                }
            }
        }
    }
    public static Result<Integer, String> inSplitIf() {
        var total = 0;
        loop$1: for (final var i : List.of(1, 2, 3, 4)) {
            do {
                if (!(i > 3)) {
                    final var $t1 = small(i);
                    if ($t1.isErr()) return Result.propagate($t1);
                    if (!(Result.unwrap($t1))) {
                        break;
                    }
                }
                try {
                    send(i);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    break loop$1;
                }
                total += i;
            } while (false);
        }
        return new Ok<>(total);
    }
    public static String withVar() {
        var last = 0;
        for (final var i : List.of(1, 2, 3)) {
            last = i;
            final var $c1 = last;
            try {
                send($c1);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return e.getMessage() + " after " + last;
            }
        }
        return "none";
    }
    public static void inLambda() {
        List.of(1, 3, 4, 5).stream().forEach(x -> {
            try {
                send(x);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return;
            }
            System.out.println("lambda " + x);
        });
    }
    public static void main(String[] args) {
        loops();
        System.out.println(first(1) + " " + first(2));
        System.out.println(describe("12") + ", " + describe("x"));
        quiet(1);
        quiet(2);
        nested();
        inWhen();
        System.out.println(inSplitIf());
        System.out.println(withVar());
        inLambda();
    }
}
