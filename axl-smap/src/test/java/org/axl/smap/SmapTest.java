package org.axl.smap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.axl.compiler.LineMap;
import org.junit.jupiter.api.Test;

class SmapTest {

    @Test
    void groupsConsecutiveJavaLinesOfOneAxlLine() {
        Smap smap = Smap.of(new LineMap("app/Main.axl", new int[] {1, 0, 0, 4, 4, 4, 5, 0}), "Main.java");
        assertEquals("""
                SMAP
                Main.java
                Java
                *S Axolotl
                *F
                + 1 Main.axl
                app/Main.axl
                *L
                1#1:1
                4#1:4,3
                5#1:7
                *E
                """, smap.format());
    }

    @Test
    void rewrittenSmapMapsAxlLinesToThemselves() {
        Smap smap = Smap.rewritten(new LineMap("app/Main.axl", new int[] {0, 1, 1, 0, 4, 5, 6, 9, 0}));
        assertEquals("""
                SMAP
                Main.axl
                Java
                *S Axolotl
                *F
                + 1 Main.axl
                app/Main.axl
                *L
                1#1:1
                4#1,3:4
                9#1:9
                *E
                """, smap.format());
        assertTrue(smap.isRewritten());
        assertEquals(smap, Smap.parse(smap.format()));
        assertEquals(5, Smap.parse(smap.format()).axlLine(5));
        assertEquals(0, Smap.parse(smap.format()).axlLine(2));
        assertFalse(Smap.of(new LineMap("app/Main.axl", new int[] {1}), "Main.java").isRewritten());
    }

    @Test
    void parsesWhatItFormats() {
        Smap smap = Smap.of(new LineMap("app/Main.axl", new int[] {1, 0, 3, 3, 2}), "Main.java");
        Smap parsed = Smap.parse(smap.format());
        assertEquals(smap, parsed);
    }

    @Test
    void parsesRepeatCounts() {
        Smap parsed = Smap.parse("""
                SMAP
                X.java
                Java
                *S Axolotl
                *F
                1 X.axl
                *L
                10#1,2:20,3
                *E
                """);
        assertEquals("X.axl", parsed.axlFileName());
        assertEquals(10, parsed.axlLine(20));
        assertEquals(10, parsed.axlLine(22));
        assertEquals(11, parsed.axlLine(23));
        assertEquals(11, parsed.axlLine(25));
        assertEquals(0, parsed.axlLine(26));
    }
}
