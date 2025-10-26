# Changelog

## Stage 0: skeleton and runtime

- Gradle multi-project with all modules, version catalog, Java 21 toolchain.
- CI on Linux and Windows runs `./gradlew check`.
- `axl-runtime`: `Result`, `Ok`, `Err`, `ThrowingSupplier`, `Nulls` in package `axl.lang`.
- Runtime is published as `org.axl:axl-runtime` via `./gradlew :axl-runtime:publishToMavenLocal`.

## Stage 1: translator

- ANTLR grammar, AST, name and placement checks, Java generator with a line map (`*.axlmap`, see `docs/axlmap.md`).
- Generated code is compiled with `javax.tools.JavaCompiler --release 21`; javac errors are moved to `.axl` lines.
- Conformance suite in `tests/conformance` with golden `.java` files, expected errors and program output.
- `axl build <src> -d <out>` and `axl run <file.axl>`.
- Decisions taken where the specification is silent are listed in `docs/decisions.md`; error codes in `docs/errors.md`.

Known limitations: `try` only in the two statement forms; no `for` loops, arrays, casts or classes; javac messages are shown as javac words them, except a mismatched `try` error type.

## Stage 2: javac, Error Prone and NullAway

- Generated code is compiled through `JavacTask` with Error Prone and NullAway in JSpecify mode.
- Clear messages for a mismatched `try` error type (AXL101), nullable dereferences (AXL102) and members imported without `fn`/`val` (AXL103).
- Conformance cases for smart casts after `if (x != null)`, early `return`, `&&` and `||`.
- JMH benchmark of `Nulls.or`/`Nulls.map` with results in `docs/perf.md`.
- `--inline-elvis` expands a statement-level `?:` without a lambda.

Known limitations: running Error Prone in process needs JVM options for `jdk.compiler`; other NullAway messages keep their own wording.

## Stage 3: Gradle plugin

- Plugin `org.axl.lang`: `src/<sourceSet>/axl` source sets, cacheable incremental `generateAxl` task, generated sources wired into `compileJava`, `axl-runtime` added automatically.
- Error Prone and NullAway on every `JavaCompile` through `net.ltgt.errorprone`.
- javac plugin `AxlDiagnostics` reports errors in generated files at the `.axl` file and line.
- Sample `samples/multi-module-gradle` with `.axl` and `.java` calling each other and JUnit tests, built by TestKit tests (also on Gradle 8.5 and with the configuration cache).

Known limitation: `compileJava` always runs in a forked JVM because the javac plugin and Error Prone need compiler internals.

## Stage 4: Maven plugin

- `axl-maven-plugin` with goals `generate`, `generate-test` (incremental by SHA-256 in `target/axl-state.json`) and `check`.
- Parameters `javaRelease`, `inlineElvis`, `smap`, `skip`.
- `samples/maven-app` with the full Error Prone and NullAway configuration; errors in `.axl` code are shown at their `.axl` lines.
- Archetype `axl-archetype-quickstart`; its integration test builds a generated project.
- Invoker integration tests on `samples/maven-app` and on projects with translation and null errors.

Known limitations: Error Prone needs `.mvn/jvm.config`; the plugin version in the POMs is updated by hand.
