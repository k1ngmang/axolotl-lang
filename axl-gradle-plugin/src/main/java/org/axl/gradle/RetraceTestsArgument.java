package org.axl.gradle;

import java.util.List;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.process.CommandLineArgumentProvider;

/** Lets JUnit load the retracing extension from axl-smap, which rewrites failures to .axl lines. */
final class RetraceTestsArgument implements CommandLineArgumentProvider {

    private final Provider<Boolean> enabled;

    RetraceTestsArgument(Provider<Boolean> enabled) {
        this.enabled = enabled;
    }

    @Input
    public Provider<Boolean> getEnabled() {
        return enabled;
    }

    @Override
    public Iterable<String> asArguments() {
        return enabled.get() ? List.of("-Djunit.jupiter.extensions.autodetection.enabled=true") : List.of();
    }
}
