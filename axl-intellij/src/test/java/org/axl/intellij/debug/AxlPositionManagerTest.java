package org.axl.intellij.debug;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AxlPositionManagerTest {

    @Test
    public void fileClassAndItsNestedClassesBelongToTheFile() {
        assertTrue(AxlPositionManager.isCompiledFrom("app.Shapes", "app.Shapes"));
        assertTrue(AxlPositionManager.isCompiledFrom("app.Shapes$Counter", "app.Shapes"));
        assertTrue(AxlPositionManager.isCompiledFrom("app.Shapes$Shape$Circle", "app.Shapes"));
    }

    @Test
    public void classesWithTheSamePrefixDoNotBelongToTheFile() {
        assertFalse(AxlPositionManager.isCompiledFrom("app.ShapesTest", "app.Shapes"));
        assertFalse(AxlPositionManager.isCompiledFrom("other.Shapes", "app.Shapes"));
    }
}
