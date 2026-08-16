package org.axl.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MessagesTest {

    @Test
    void asksForTheTypeOfALocalWhoseFilterDoesNotNarrow() {
        String[] javaLines = {
                "package app;",
                "public final class Main {",
                "    public static void main(String[] args) {",
                "        System.out.println(d.stream().filter(it -> it instanceof Err(var it$2)).map(Nulls.nonNull(it -> it.error())).toList());",
                "    }",
                "}"};
        String message = Messages.translate("cannot find symbol; symbol: method error(); location: variable it of type "
                        + "axl.lang.Result<java.lang.Integer,java.lang.String>", javaLines, 4, -1,
                "    println(d |> filter { it is Err } |> map { it.error() })").message();
        assertEquals("`it` of type `Result<Int, String>` may be an `Ok`, so it has no `error()` yet; `filter { it is Err }` "
                + "narrows the elements only when the type of `d` is known: write it, `val d: List<Result<Int, String>> = ...`", message);
    }
}
