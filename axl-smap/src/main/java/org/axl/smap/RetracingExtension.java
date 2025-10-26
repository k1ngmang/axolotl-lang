package org.axl.smap;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

/**
 * Rewrites stack traces of failing tests to .axl lines, using the SMAPs of the loaded classes,
 * before build tools and IDEs see them. Registered through ServiceLoader; JUnit picks it up when
 * `junit.jupiter.extensions.autodetection.enabled=true`.
 *
 * <p>A launcher TestExecutionListener would be too late: JUnit notifies listeners of finished tests
 * in reverse order, so the build tool's own listener records the failure first.
 */
public final class RetracingExtension implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw retrace(context, throwable);
    }

    @Override
    public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw retrace(context, throwable);
    }

    @Override
    public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw retrace(context, throwable);
    }

    @Override
    public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw retrace(context, throwable);
    }

    @Override
    public void handleAfterAllMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw retrace(context, throwable);
    }

    private static Throwable retrace(ExtensionContext context, Throwable throwable) {
        Retracer.of(context.getRequiredTestClass().getClassLoader()).retrace(throwable);
        return throwable;
    }
}
