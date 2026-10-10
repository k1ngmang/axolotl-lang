package org.axl.bench;

import axl.lang.Nulls;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * `?:` and `?.` as generated (Nulls with a lambda) against hand-written null checks, and a call with the check
 * of its parameter against one without.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class NullsBenchmark {

    @Param({"present", "absent"})
    public String input;

    private String value;
    private String fallback;

    @Setup
    public void setUp() {
        value = input.equals("present") ? "axolotl" : null;
        fallback = "stranger";
    }

    @Benchmark
    public String elvisNulls() {
        String f = fallback;
        return Nulls.or(value, () -> f);
    }

    @Benchmark
    public String elvisManual() {
        String v = value;
        return v != null ? v : fallback;
    }

    @Benchmark
    public Integer safeCallNulls() {
        return Nulls.map(value, s -> s.length());
    }

    @Benchmark
    public Integer safeCallManual() {
        String v = value;
        return v == null ? null : v.length();
    }

    /** A call of a public function, which checks its non-null parameter on entry, against one without the check. */
    @Benchmark
    public int callChecked() {
        return checkedLength(fallback);
    }

    @Benchmark
    public int callUnchecked() {
        return uncheckedLength(fallback);
    }

    private static int checkedLength(String text) {
        Nulls.param(text, "text");
        return text.length();
    }

    private static int uncheckedLength(String text) {
        return text.length();
    }
}
