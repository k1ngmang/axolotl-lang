package org.axl.intellij.debug;

import com.intellij.debugger.ui.tree.LocalVariableDescriptor;
import com.intellij.debugger.ui.tree.NodeDescriptor;
import com.intellij.debugger.ui.tree.NodeDescriptorNameAdjuster;

/**
 * Marks the translator's own locals (`$t1`, `$n1`, `$ex1`, `$c1`) in the Variables view. The
 * platform has no extension point to hide a local variable, so they are labeled instead.
 */
public final class AxlGeneratedNameAdjuster extends NodeDescriptorNameAdjuster {

    @Override
    public boolean isApplicable(NodeDescriptor descriptor) {
        return descriptor instanceof LocalVariableDescriptor && descriptor.getName() != null
                && descriptor.getName().startsWith("$");
    }

    @Override
    public String fixName(String name, NodeDescriptor descriptor) {
        return "Axolotl internal " + name;
    }
}
