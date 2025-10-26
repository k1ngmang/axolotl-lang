package org.axl.intellij.debug;

import com.intellij.debugger.PositionManager;
import com.intellij.debugger.PositionManagerFactory;
import com.intellij.debugger.engine.DebugProcess;

public final class AxlPositionManagerFactory extends PositionManagerFactory {

    @Override
    public PositionManager createPositionManager(DebugProcess process) {
        return new AxlPositionManager(process);
    }
}
