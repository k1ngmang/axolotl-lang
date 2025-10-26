package org.axl.intellij.debug;

import com.intellij.debugger.engine.JavaDebugAware;
import com.intellij.psi.PsiFile;
import org.axl.intellij.psi.AxlFile;

/** Allows Java line breakpoints in .axl files. */
public final class AxlJavaDebugAware extends JavaDebugAware {

    @Override
    public boolean isBreakpointAware(PsiFile file) {
        return file instanceof AxlFile;
    }
}
