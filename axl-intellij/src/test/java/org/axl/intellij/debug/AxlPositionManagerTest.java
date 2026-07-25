package org.axl.intellij.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.intellij.debugger.NoDataException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import java.lang.reflect.Proxy;
import java.util.List;
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

    @Test
    public void readsFileAndLineFromTheAxolotlStratum() throws Exception {
        // The class file says Main.axl and .axl lines itself, and the stratum maps each line to itself.
        Location location = location(List.of("Java", "Axolotl"), "app/Main.axl", 12, 12);
        assertEquals(new AxlPositionManager.AxlLocation("app/Main.axl", 12), AxlPositionManager.axlLocation(location));
    }

    @Test
    public void readsLocationsOfClassesFromOlderBuilds() throws Exception {
        // The default stratum shows generated Java lines there; the Axolotl stratum still has the .axl line.
        Location location = location(List.of("Java", "Axolotl"), "app/Main.axl", 8, 13);
        assertEquals(new AxlPositionManager.AxlLocation("app/Main.axl", 8), AxlPositionManager.axlLocation(location));
    }

    @Test
    public void classesWithoutTheStratumAreLeftToTheJavaPositionManager() {
        Location location = location(List.of("Java"), "app/Main.java", 13, 13);
        assertThrows(NoDataException.class, () -> AxlPositionManager.axlLocation(location));
        assertThrows(NoDataException.class, () -> AxlPositionManager.axlLocation(null));
    }

    @Test
    public void locationsWithoutALineAreNotPositions() {
        Location location = location(List.of("Java", "Axolotl"), "app/Main.axl", -1, 3);
        assertThrows(NoDataException.class, () -> AxlPositionManager.axlLocation(location));
    }

    /** A JDI location whose Axolotl stratum says `axlLine` and whose default stratum says `defaultLine`. */
    private static Location location(List<String> strata, String path, int axlLine, int defaultLine) {
        ReferenceType type = (ReferenceType) Proxy.newProxyInstance(AxlPositionManagerTest.class.getClassLoader(),
                new Class<?>[] {ReferenceType.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "availableStrata" -> strata;
                    case "name" -> "app.Main";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Location) Proxy.newProxyInstance(AxlPositionManagerTest.class.getClassLoader(),
                new Class<?>[] {Location.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "declaringType" -> type;
                    case "lineNumber" -> args == null ? defaultLine : "Axolotl".equals(args[0]) ? axlLine : defaultLine;
                    case "sourcePath" -> path;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
