package org.axl.compiler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TranslatorTest {

    private static final String PARSE = """
            package util

            fn parse(s: String) -> Result<Int, String> =
                attempt Integer.parseInt(s) else e -> "not a number: " + s

            fn doubled(s: String) -> Result<Int, String> {
                val n = try parse(s)
                return Ok(n * 2)
            }
            """;

    @Test
    void outputIsDeterministic() {
        String first = Translator.translate("util/Parse.axl", "Parse.axl", PARSE).unit().javaSource();
        String second = Translator.translate("util/Parse.axl", "Parse.axl", PARSE).unit().javaSource();
        assertEquals(first, second);
    }

    @Test
    void lineMapPointsTryExpansionAtItsLine() {
        GeneratedUnit unit = Translator.translate("util/Parse.axl", "Parse.axl", PARSE).unit();
        List<String> java = unit.javaSource().lines().toList();
        int tempLine = java.indexOf("        final var $t1 = parse(s);") + 1;
        assertEquals(7, unit.lineMap().axlLine(tempLine));
        assertEquals(7, unit.lineMap().axlLine(tempLine + 1));
        assertEquals(7, unit.lineMap().axlLine(tempLine + 2));
        assertEquals(8, unit.lineMap().axlLine(tempLine + 3));
        assertEquals(1, unit.lineMap().axlLine(1));
        assertEquals("util/Parse.axl", unit.lineMap().source());
        assertEquals(java.size(), unit.lineMap().javaLineCount());
    }

    @Test
    void lineMapSurvivesJson() {
        LineMap map = new LineMap("a/B \"x\".axl", new int[] {1, 0, 3});
        LineMap read = LineMap.fromJson(map.toJson());
        assertEquals(map.source(), read.source());
        assertArrayEquals(new int[] {1, 0, 3}, new int[] {read.axlLine(1), read.axlLine(2), read.axlLine(3)});
    }

    @Test
    void nearestLineFallsBackToMappedLineAbove() {
        LineMap map = new LineMap("A.axl", new int[] {1, 0, 0, 4});
        assertEquals(1, map.nearestAxlLine(3));
        assertEquals(4, map.nearestAxlLine(9));
    }

    @Test
    void newlineEndsAStatementBeforeParenthesis() {
        Translator.Result result = Translator.translate("A.axl", "A.axl", """
                package p
                fn f() -> Int {
                    val a = 1
                    (a)
                    return a
                        - 1
                }
                """);
        // `(a)` and `- 1` are statements of their own, which do nothing.
        assertEquals(List.of(4, 6), result.diagnostics().stream().map(AxlDiagnostic::line).toList());
    }

    @Test
    void stringsLongerThanAJavaConstantAreRejected() {
        assertTrue(stringDiagnostics("a".repeat(65535)).isEmpty());
        assertTrue(stringDiagnostics("\\n".repeat(65535)).isEmpty());
        List<AxlDiagnostic> tooLong = stringDiagnostics("a".repeat(65536));
        assertEquals(1, tooLong.size());
        assertEquals(3, tooLong.get(0).line());
        assertEquals(13, tooLong.get(0).column());
        assertTrue(tooLong.get(0).message().startsWith("this string is too long for Java"), tooLong.get(0).message());
        assertEquals(1, stringDiagnostics("é".repeat(32768)).size());
    }

    private static List<AxlDiagnostic> stringDiagnostics(String text) {
        String source = "package p\nfn main() {\n    println(\"" + text + "\")\n}\n";
        return Translator.translate("A.axl", "A.axl", source).diagnostics();
    }

    @Test
    void nestedIfsParseQuickly() {
        StringBuilder chain = new StringBuilder("package p\nfn f(x: Int) {\n    if (x > 0) {\n        println(0)\n");
        StringBuilder nested = new StringBuilder("package p\nfn g(x: Int) {\n");
        for (int i = 1; i <= 30; i++) {
            chain.append("    } else if (x > ").append(i).append(") {\n        println(").append(i).append(")\n");
            nested.append("if (x > ").append(i).append(") {\n");
        }
        chain.append("    }\n}\n");
        nested.append("println(x)\n").append("}\n".repeat(30)).append("}\n");
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertTrue(Translator.translate("A.axl", "A.axl", chain.toString()).diagnostics().isEmpty());
            assertTrue(Translator.translate("B.axl", "B.axl", nested.toString()).diagnostics().isEmpty());
        });
    }
}
