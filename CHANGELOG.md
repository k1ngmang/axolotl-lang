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

## Stage 5: debugging and stack traces

- `axl-smap`: adds a JSR-45 `SourceDebugExtension` (stratum `Axolotl`) to compiled classes from the line maps; `LineNumberTable` and `SourceFile` are untouched.
- The Gradle plugin adds the SMAP after `compileJava`; the Maven plugin has goals `axl:smap` and `axl:smap-test`; `axl build` adds it too.
- `axl retrace` rewrites stack traces using `.axlmap` files or SMAPs; `axl run` prints exceptions with `.axl` lines.
- Failing JUnit tests are reported with `.axl` lines in Gradle and Maven.
- A JDI test sets a breakpoint on an `.axl` line through the `Axolotl` stratum.

## Stage 6: language server

- `axl-lsp` (LSP4J, stdio): diagnostics while typing and, on save, javac/Error Prone/NullAway diagnostics of the module; completion including Java members via `javax.lang.model`; go to definition in `.axl` and Java; hover with types and generated Java; semantic tokens; command `axl.showGeneratedJava`.
- TextMate grammar `axl.tmLanguage.json`.
- VS Code extension in `editors/vscode`, packaged with `./gradlew :axl-lsp:vsix`.
- Gradle task `axlLspConfig` and Maven goal `axl:lsp-config` write the module description the server reads.

Known limitations: no incremental document sync; completion does not follow call chains.

## Stage 7: IntelliJ IDEA plugin

- File type, ANTLR-based lexer, `PsiBuilder` parser, highlighting with color settings, brace matching, commenter, folding, structure view.
- Completion, go to declaration, find usages and rename across `.axl` and Java; translator rules as an annotator with quick fixes; javac/NullAway errors on save.
- Java sees `.axl` functions and values without a build (`PsiElementFinder`, `PsiShortNamesCache`).
- Run icon for `fn main()`; breakpoints and stepping in `.axl` files through the `Axolotl` stratum.
- Show Generated Java, Convert Java File to Axolotl, LSP integration in Ultimate, Maven source roots.
- Platform tests for parsing, highlighting, completion, rename, find usages, quick fixes and Java interop; `verifyPlugin` for IntelliJ IDEA 2024.2 and 2025.2.

Known limitations: generated locals are labeled, not hidden, in the debugger; the debugger is checked by the manual scenario in `docs/intellij-debug-check.md`.

## Stage 8: `try` in expressions and pipes

- `try` works anywhere in an expression of a function returning `Result`, with left-to-right evaluation order kept by temporaries; `&&`, `||` and `?:` become `if` chains, `while` conditions are evaluated inside the loop.
- Pipes `xs |> filter { } |> map { }` expand to Java streams; `|> mapResult { }` expands to a loop that returns the first `Err`.
- Conformance cases with side effects check the evaluation order; a JDI test stops on a line with a lifted `try`.
- The language server, the IntelliJ plugin and the TextMate grammar know `|>`; the language is described in `docs/language.md`.

## Stage 9: 0.1.0

- `a!!` asserts that a value is not null.
- Top-level `val` types are inferred, by local rules or by javac over a draft translation of the module.
- Strict null mode (`strictNull` in Gradle and Maven, `--strict-null` on the command line, `"strictNull"` in `axl.json`): values from Java code without nullness annotations are nullable until checked (AXL104).
- Common javac errors are reworded with Axolotl names and types; NullAway's nullable argument error is AXL102.
- Documentation site built with MkDocs: getting started, installation, migrating from Java, leaving Axolotl, FAQ; checked in CI with `mkdocs build --strict`.
- Version 0.1.0 everywhere. Nothing is published to a public repository yet; `./gradlew publishToMavenLocal` installs the artifacts locally.

Known limitations: no `for` loops, arrays, casts or classes; strict null mode narrows locals only, like NullAway.

## Audit fixes for 0.1.0

An audit of the whole toolchain found about 95 problems; all of them are fixed, each with a conformance case or test.

- Null safety: `a ?: b` with a nullable `b`, `Ok(null)`, `attempt` of a nullable value, `mapResult` and pipe `map` producing null, null assigned to a non-null local, and top-level values read before they are set are now errors instead of runtime NPEs.
- Wrong results: `- -x` decremented `x`; a `try` in a call chain or an assigned value ran before the code to its left.
- Code that did not compile or crashed the compiler: `println(a?.trim())`, `try` in calls on classes such as `Math`, empty lambdas, `?:` with numbers on `Long?`, statement forms of `mapResult`, nested pipes, non-denotable inferred types and more.
- Strict null mode checks arguments, returns, written types, unboxing, copies, loops and lambda parameters, and accepts pipes.
- Diagnostics: errors point to the right line and column, do not show generated names, and javac, NullAway and syntax errors are worded in Axolotl terms; new codes AXL014 to AXL016 and AXL105.
- Build tools: incremental builds retype values inferred from changed files; Maven state per output directory; Gradle `smap` is a compile input and the runtime is an `api` dependency of libraries; the CLI reports missing files and directories and lets programs' threads finish.
- Editors: UTF-16 positions in IntelliJ and the language server, overloads in rename and find usages, parser parity with the grammar, and several language server fixes.
- `Result.mapErr` was added; parsing is no longer quadratic on long expressions.
