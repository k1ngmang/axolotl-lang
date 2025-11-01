# FAQ

## Why NullAway instead of a type checker of its own?

NullAway already checks nullness of Java code in JSpecify mode, it is fast, and it runs inside
javac, so the same check covers Axolotl and the Java code next to it. Axolotl writes `T?` as
`@Nullable T` and makes every generated class `@NullMarked`; NullAway does the rest. The rules for
when a value is known to be non-null (after `if (x != null)`, an early `return`, inside `&&`) are
NullAway's.

## Why do I need `--add-exports` options?

Error Prone, which runs NullAway, and the javac plugin that moves errors to `.axl` lines use
compiler internals. The Gradle plugin forks `compileJava` with the options; for Maven they go into
`.mvn/jvm.config`, and the command-line tools start with them. Without them the build fails with a
message that names the missing options.

## Can I turn NullAway off?

Yes: `axl { nullAway = false }` in Gradle, no Error Prone configuration in Maven, `--no-null-checks`
on the command line. Axolotl still compiles, but `T?` is then only documentation.

## Does NullAway check values that come from Java libraries?

Only when the library is annotated. NullAway trusts unannotated code, so `System.getenv("X").length()`
compiles. Strict null mode makes Axolotl treat values from unannotated Java as nullable instead; see
[the language](language.md#java-code-without-nullness-annotations).

## NullAway says a field may be null after I checked it

NullAway narrows locals and parameters, not fields, because another method could change the field
between the check and the use. Copy the field to a `val` first and check the `val`.

## Why does an error mention generated Java?

It should not: errors in generated files are moved to the `.axl` line and reworded where the Java
wording would be confusing. If you see a path under `build/generated/axl` or
`target/generated-sources/axl`, the javac plugin is not configured; see [Maven](maven.md) for the
compiler arguments. Please report messages that still talk about generated names such as `$t1`.

## How do I debug Axolotl code?

Classes carry a JSR-45 SMAP, so IntelliJ IDEA and any JDI debugger stop at `.axl` lines. Stack
traces of failing tests and of `axl run` show `.axl` lines; `axl retrace` rewrites any other trace.
See [debugging](debugging.md).

## Is there a runtime cost?

`?:` and `?.` expand to small static calls with lambdas that the JIT inlines; the benchmark in
[performance](perf.md) shows no measurable cost. Everything else expands to the Java you would
write by hand.
