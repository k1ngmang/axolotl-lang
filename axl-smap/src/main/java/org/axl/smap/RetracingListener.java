package org.axl.smap;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Registered through ServiceLoader: rewrites stack traces of failed tests to .axl lines before
 * build tools and IDEs report them. Uses the SMAPs of the loaded classes.
 */
public final class RetracingListener implements TestExecutionListener {

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
        result.getThrowable().ifPresent(t -> {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Retracer.of(loader != null ? loader : getClass().getClassLoader()).retrace(t);
        });
    }
}
