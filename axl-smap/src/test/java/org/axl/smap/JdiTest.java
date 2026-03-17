package org.axl.smap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.ClassPrepareRequest;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.axl.javac.RuntimeClasspath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A debugger sees the Axolotl stratum and can stop at an .axl line. */
class JdiTest {

    @Test
    void breakpointOnAxlLine(@TempDir Path tmp) throws Exception {
        stopAt(tmp, "app.Main", 13);
    }

    /** `try` inside an expression expands to several Java lines; all of them belong to the .axl line. */
    @Test
    void breakpointOnLineWithLiftedTry(@TempDir Path tmp) throws Exception {
        stopAt(tmp, "app.Main", 8);
    }

    @Test
    void breakpointInMethodOfNestedClass(@TempDir Path tmp) throws Exception {
        stopAt(tmp, "app.Main$Joiner", 20);
    }

    private void stopAt(Path tmp, String className, int axlLine) throws Exception {
        Fixture fixture = new Fixture(tmp);
        fixture.inject();
        String classpath = fixture.classes + File.pathSeparator + RuntimeClasspath.entries().getFirst();

        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("main").setValue("app.Main");
        args.get("options").setValue("-cp \"" + classpath + "\"");
        VirtualMachine vm = connector.launch(args);
        try {
            ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter(className);
            prepare.enable();

            Location stoppedAt = null;
            while (stoppedAt == null) {
                EventSet events = vm.eventQueue().remove(30_000);
                if (events == null) {
                    throw new AssertionError("no event from the debuggee");
                }
                for (Event event : events) {
                    if (event instanceof ClassPrepareEvent prepared) {
                        ReferenceType type = prepared.referenceType();
                        assertTrue(type.availableStrata().contains("Axolotl"), type.availableStrata().toString());
                        assertEquals(List.of("Main.axl"), type.sourceNames("Axolotl"));
                        List<Location> locations = type.locationsOfLine("Axolotl", "Main.axl", axlLine);
                        assertFalse(locations.isEmpty());
                        vm.eventRequestManager().createBreakpointRequest(locations.getFirst()).enable();
                    } else if (event instanceof BreakpointEvent breakpoint) {
                        stoppedAt = breakpoint.location();
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        throw new AssertionError("the debuggee ended before the breakpoint");
                    }
                }
                // The launched VM starts suspended; resuming each event set lets it run to the next event.
                if (stoppedAt == null) {
                    events.resume();
                }
            }
            assertEquals(axlLine, stoppedAt.lineNumber("Axolotl"));
            assertEquals("Main.axl", stoppedAt.sourceName("Axolotl"));
            assertEquals("Main.java", stoppedAt.sourceName("Java"));
        } finally {
            vm.process().destroy();
        }
    }
}
