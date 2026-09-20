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

- `axl-maven-plugin` with goals `generate`, `generate-test` (incremental by SHA-256 in `target/axl/<out>-from-<src>.state.json`) and `check`.
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

Known limitations: no incremental document sync.

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

## Second audit fixes for 0.1.0

A second audit found about 75 more problems, mostly a layer deeper and a dozen caused by the first round of fixes; all of them are fixed, each with a conformance case or test.

- Null safety: a javac checker that always runs covers what NullAway trusts in JDK generics: lambdas returning null or `Int?` into JDK interfaces, `?` in type arguments (`List<String?>.get`, pipe elements, `|> first`, `sorted`), nullable values stored in locals without `?`. Inferred top-level types keep the `?` of type arguments and of arrays, cross-file inference no longer depends on file order, AXL016 follows lambdas, references and other files, and `a.f += try g()` reads `a.f` first.
- Strict null mode: fallbacks of `?:`, `attempt`, `Ok`, stream arguments, unboxing in conditions and `+=`, lambdas and references passed to or kept for Java, loops of any length; common JDK methods are trusted.
- Code that did not compile or crashed: `?: 0` on `Number?`, `Object?`, primitives and after `?.`; `?:` with `try` on the right; `?.` arguments in lambdas, top-level values and `attempt`; `Thread.State`; null checks before `&& try`; copies in block lambdas; `Ok(x)`, `List.of()` and diamonds before a `try`; bad `\u` escapes, deep nesting, nested `it`, `a < b, c > (d)`.
- Diagnostics: the Gradle and Maven plugin gives the CLI's messages; lines of multi-line lambdas, parameter lists and `try`; columns in code points, outside comments and across generated lines; literals, statements on one line, statements that do nothing and more javac, Error Prone and syntax messages are checked or reworded; warnings and notes about generated code are hidden or name the `.axl` file.
- Build tools: duplicate classes in incremental builds, generated Java source directories and custom source sets in Gradle, sibling modules and removed source directories in Maven, `axl run` options after the file, `-d` at or above the sources, non-UTF-8 files.
- Editors: overload resolution in IntelliJ rename and the language server, the "move `try`" quick fix, duplicate classes, cross-file inference and edited lines in the language server, the Java converter's output.

## Third audit fixes for 0.1.0

A third audit found about 110 more problems; they are fixed, each with a conformance case or test, except the few listed at the end.

- Null safety: `?` in type arguments is followed through nested types, supertypes, `?.`, copies, `collect`, receivers with several type parameters, constructors and fields of Java generic classes. Method references are checked like lambdas. `Map.get` counts as nullable, as NullAway models it, and in strict mode `List<String>.get` is trusted. `Ok`, `Err` and `attempt` in a lambda of a written type, unboxing into Java parameters, a lambda handed on to Java through an Axolotl parameter and `Collections.sort` are checked. `Arrays.asList`, `Collections.singletonList` and `Optional.ofNullable` take null. A null check in one function no longer narrows another.
- Initialization order: AXL016 scopes locals by block, follows lambdas kept in locals and calls into functions of other files.
- Wrong results: `--inline-elvis` with two number types from locals or parameters, and a `var` used through `?:` or `attempt` in a lambda, which is now rejected like a direct use.
- Code that did not compile: `c && f()`, `c || f()` and `a ?: f()` as statements, pipes ending in `forEach` after a `try`, lambda parameters written with a number type, more target-typed factories before a `try`, null checks on the left of `&& try` and `|| try` in `if`, `else` and `while`, `?:` with `try` and no written type, `?.` inside `?:` passed to an overloaded method, number fallbacks of another type, constructor calls qualified by a package.
- Checks: imports and functions that clash with names of the generated code, characters that do not fit in a `Character`, `\s`, names outside A to Z, `3.f`, `1.e5`, `5.toString()`, doubles out of range, `;` after an `if` block followed by a statement, package and imports on one line.
- Diagnostics: the plugin keeps different errors at one position, errors of the null checker come before javac's summary and before class files are written, columns with tabs, a byte order mark, block comments and generated `it`, lines of top-level values on the next line, positions of unfinished declarations, and more javac and Error Prone messages in Axolotl terms.
- Build tools and editors: see the commits; among them `axl:check` with Error Prone, Maven state per execution, overlapping source directories, `=` in paths, killed `axl check` runs, hover, drafts and caches in the language server, and the Java converter.

Left as they are: a nested class whose name is all capitals before a `try` (`Outer.IO.f(try g())`) is taken for a value; qualified constructors with type arguments (`java.util.ArrayList<String>()`) do not parse; NullAway's narrowing of calls (`if (f() != null) f().length()`) is kept.

## Language additions

- `for (x in xs)` loops over any `Iterable` or array.
- `data class` (a Java record) and `sealed interface`, nested in the class of their file.
- `x is T` and `x !is T`, with smart casts of a `val` or parameter where the test holds.
- `when` with type tests and constants, as a statement, after `return` or as a value.
- `class` with parameters, properties and methods, and files that are the type they declare.
- `if (c) a else b` as a value, and `throw`.
- `enum` with constants.

## Compiler written in Axolotl

- The syntax tree, the checks (`Checker`, `InitOrder`, `TypeInference` and others), the code generator and the builder of the syntax tree are `.axl` files in `axl-compiler/src/main/axl`; the Java they translate to is checked in under `axl-compiler/bootstrap`.
- `./gradlew check` rebuilds the compiler from that snapshot and fails unless it translates its own sources to the same files (`verifyBootstrap`); `./gradlew updateBootstrap` replaces the snapshot. See `docs/self-hosting.md`.
- `SourceParser` (it subclasses ANTLR classes), `Translator` and the small records of the public API stay Java.

## Fourth audit fixes

A fourth audit checked the new language features, the self-hosted compiler, the editors and the build. Its findings are fixed, each with a conformance case or test, except the few listed at the end.

- Crashes and wrong results: `if` and `is` values inside lifted code, unboxing of a null branch next to a number (`if (c) maybe() else 0`), `var` properties no longer narrowed, `+=` on a property with `try`, `throw` of a nullable value and statements in strict mode.
- Classes: properties read before they are set (AXL016 follows constructors, methods, `this` and returned lambdas), values an interface requires (AXL017), assignment to a `val` property, reserved and duplicate member names, enums as the type of their file.
- `when`: `Long`, `Double` and `Boolean` subjects, `null` branches next to type tests, negative and named constants, exhaustive enums, captured variables in value branches, `Result` subjects.
- Diagnostics: misspelled keywords (`fun`), Java habits (`new`, casts, `let`, `int x`), `when {` without a subject, a missing `->`, `if` statements without braces, errors inside a multi-line `if` value on their own line, no duplicate errors for class parameters, generated names hidden, and more javac messages in Axolotl terms.
- Build: line endings fixed by `.gitattributes`, `updateBootstrap` compiles the new snapshot before replacing the old one, the long chains in the generator are no longer walked again from each operator.
- Editors: nested types and constructors in IntelliJ light classes, rename and find usages of members, narrowing, hover, completion and outline for classes, data classes and enums in the language server.

Left as they are: mixing `Int?` and `Long?` branches in one value, `Int? ?: 0L`, and `var x = when` with lambdas in the branches (the message asks for the type).

## Unreleased (0.2.0)

Everything after the 0.1.0 release of 2026-10-17, which has the `axl` zip and the VS Code and IntelliJ plugins.

- Installers that bring their own Java runtime and put `axl` on the `PATH`: a setup program for Windows, a `.pkg` for macOS, a `.deb` for Debian 12 and Ubuntu 22.04 or newer and a tarball with `install.sh` for other Linux systems. The runtime has every module of Java SE, so code that uses `java.awt` or `java.beans` compiles and runs, and the `.deb` depends on the X11, ALSA and font libraries; `/usr/bin/axl` is registered with `update-alternatives`. The runtime keeps the line tables of the JDK, so stack traces show `Objects.java:233` rather than `Unknown Source`. See `docs/install.md`.
- `axl setup` copies the libraries and the Maven and Gradle plugins that come with `axl` into the local Maven repository, the one named by `-Dmaven.repo.local` or `~/.m2/settings.xml` if set. The installers run it, and the `axl` zip carries the repository too. A blank `--repository` is rejected, and the repository is printed as an absolute path.
- Every `axl` command takes `--help` and `--version`, and `axl help <command>` describes one. `axl run` says that `fn main` takes no parameters when it has some; the program reads `args`.
- Version 0.2.0 in the build, the POMs, the samples and the documentation. `axl --version` and the language server take their version from the build.
- On a non-UTF-8 locale, a missing file in a directory with non-ASCII characters is reported with the same locale hint as an invalid path.
- Windows: `.axl` paths of javac errors are printed as file paths, IntelliJ starts the language server, and the Gradle and Maven plugin tests accept long and URI-style paths.
- Module descriptions: `axlLspConfig` writes a module for each source set, which reads the sources of the source sets and projects it compiles against instead of their classes but keeps its own classpath, and `axl:lsp-config` writes a main and a test module. `axl.json` now holds a list of `"modules"`, so run the task again after upgrading.
- Language server: javac errors of Java sources and of files that are not open, completion in imports and of Java members, go to Java declarations, compilation off the request thread, and modules found again when a description or source set changes. IntelliJ passes its modules and classpath to the server, checks test sources and completes members of `it`, lambda parameters and loop variables.
- Compiler: warnings about `?.` and `!!` on values that are never null, null checks that cannot fail, `var`s never assigned again and Kotlin string templates; explanations of habits from Java, Kotlin, TypeScript and Python, misspelled builtin words and misplaced code; Error Prone findings worded without Java terms. Strict mode keeps the elements and type arguments of unannotated Java nullable, nullable arguments to JDK methods that throw on null are rejected, and only locals, parameters and `val` properties are narrowed after a null check. `if` and `when` values with branches of different basic types, raw generic types and strings too long for a Java constant are rejected; `else if` chains parse in linear time.
- Build: the project builds with Gradle 9.8.1 and IntelliJ Platform Gradle Plugin 2.19.0, and the published Gradle plugin still supports Gradle 8.5. The wrappers check the SHA-256 of the Gradle and Maven distributions. With `-q` the Maven builds are quiet too.
- Workflows: CI reports every failing task, uploads the test reports and checks that every file has the version of the build. Version tags publish a GitHub release with the zip, the VS Code and IntelliJ packages and the installers, after checking that the tag is new and is the version of the build and running `./gradlew check`; the packages are built in a job without write access. The installers are built and tried on each system for pull requests, releases and once a week, and the Linux ones on every push to main too; the `.deb` is also installed on Debian 12 and Ubuntu 22.04.
- Seventh audit:
  - Initialization order follows methods called on locals, loop variables, pipe elements, list elements, stored method references and the `toString` of `"x" + box`.
  - `p.x` on a data class and private members are closed within the same file too.
  - Java's names `int`, `boolean` and `void` are rejected, and `Integer` gets a warning. `record` is allowed as a name.
  - A raw value from Java code, or after `o is List`, cannot be stored as `List<String>`.
  - A `when` over an `Int?` no longer unboxes null in its `else` branch.
  - `==` compares numbers from `?:`, `try` and `!!` by value.
  - Int literals are widened for `Long?` and `Double?`.
  - `Ok`/`Err` branches in `else if` chains, `mapResult` and `map` lambdas get their types.
  - Long `?:` chains and deeply nested `if` values compile in linear time.
  - The language server compiles one module at a time.
- Ninth audit, four more programs (an expression language with a REPL, a shop with pricing strategies, a concurrent job runner, an HTTP service with JUnit tests):
  - `==` on strings, data classes and other values with their own `equals` is an error, as for boxed numbers, since the program would compare the objects and be wrong.
  - A `when` that is returned reports a branch that ends with an `if` statement holding its values, at the `if`, instead of javac's "does not return a value" at the end of the function.
  - `m.get(k) ?: List.of()` keeps the element type of the map's values.
  - A property whose value tells its type needs none: `val items = ArrayList<String>()`, `var count = 0`.
  - The `else` of `if (r is Err)` knows the `Ok` and reads `r.value()`, and the other way round.
  - A lone `attempt conn.commit() else e -> log(e)` is the body of a lambda too, as in `Thread({ -> ... })`.
  - `sorted(byAge)` takes a `Comparator`.
  - `print(x)` and `readLine()` next to `println`.
  - A class implementing an interface of another file of the build gets the accessors of the interface's values, and is checked against the interface by the compiler itself: a missing method or a `String?` value for a `String` is reported as for an interface of the same file. The Gradle and Maven plugins read the unchanged files when translating changed ones and translate the package of a changed or removed file again, so an incremental build gives what a full one does.
  - A class of its own file that lacks a method of a Java interface, `class Worker : Runnable` without `run`, gets `` `Worker` must give `fn run()` `` instead of javac's words.
  - Test sources read the declarations of the main `.axl` sources in Gradle and Maven, and a Gradle source set those of the projects it depends on, as the modules of the language server do.
- `axl check`, `axl build` and `axl run` compile in a background daemon that keeps javac, Error Prone, NullAway and the compiler loaded: after the first command, a small program compiles and runs in about a second instead of three to seven. The first command starts the daemon. It ends after 3 hours without work (`AXL_DAEMON_IDLE`, such as `30m`), when its installation changes, or on `axl daemon --stop`; `axl daemon --status` shows it. `--no-daemon` or `AXL_DAEMON=off` compile in the command's own process, as `axl` also does whenever the daemon cannot start; `--verbose` says which. The program of `axl run` still runs in the `axl` process. On Windows the daemon is used only from a console.
- `axl fmt <files or directories>` rewrites `.axl` files in the one style of the documentation (4 spaces, one space around operators and `->`, sorted imports, at most one blank line in a row); line breaks, comments and literals stay as written. `axl fmt --check` lists the files that would change and exits with 1, for CI; `axl fmt -` formats standard input. Every result is parsed and translated again and must give the same syntax tree and Java, otherwise the file is left unchanged. The language server answers `textDocument/formatting` and IntelliJ's *Reformat Code* uses the same formatter. See `docs/fmt.md`.
- Fourteenth audit:
  - A lambda with several statements that ends without `return` is reported as such ("a lambda with several statements gives its value with `return`: write `return ...`"), also in a pipe, where it used to read as wrong arguments for `nonNull` followed by errors about `Object`.
  - A list whose elements have no common type for Java (`List<Record&Shape>`) is reported with the type to write: `val shapes: List<Shape> = ...`.
  - `get(i)` on a Java array of a primitive type suggests the `java.nio` buffer that reads it, such as `ByteBuffer.wrap(data).get(i)`; a `CharArray` is no longer called an array of `Character`.
  - A list passed where a varargs method of Java takes single values (`EnumSet.of(RED, rest)`, `Collections.addAll(set, list)`) gets an explanation: pass the values one by one, or use `addAll`.
  - An unknown name that is a top-level value of another file of the build gets the `import val` to add.
  - Error Prone findings without their own wording name types without packages and with Axolotl's names (`CompletableFuture<List<Int>>`).
  - New warning: a list given as the only value where a Java method takes `Object...` (such as `MessageFormat.format("{0}-{1}", values)`) is one value; pass `values.toArray()`.
  - A `val` without a type set from `List.of(...)`, a new collection or a pipe is passed where Java takes an array, also when the type of its elements is not known (`Map.ofEntries(entries)`, `CompletableFuture.allOf(futures)`).
  - A list goes to the varargs array of the overload with as many parameters even when another overload, such as `Files.newBufferedWriter(Path, OpenOption...)`, cannot take a list there.
  - `val shapes: List<Shape> = xs |> map { Dot(it) }` and a function returning `List<Shape>` with such a pipe compile: the `map` gives the written element type.
  - `List.of(Dot(1), Box(2))` is a `List<Shape>` when the elements share one interface of the build.
  - `attempt f() else e -> continue` at the end of a loop body no longer gets Error Prone's RedundantControlFlow warning.
  - `xs.get(0) == xs.get(1)` on a `List<Int>` compares the numbers instead of being an error.
  - `is List<*>` and `is Map<*, *>` are accepted and mean `is List` and `is Map`.
  - New warning AXL018: the `Result` of a call of the build used as a statement is dropped; keep it with `val unused = ...` if that is meant.
  - `break` or `continue` outside a loop says that a loop is needed around it, and in a lambda that a lambda ends with `return`.
  - `axl check`, `axl build` and `axl run` show paths in the form the command line gives them: absolute for absolute arguments, else relative to the working directory. Before, `check` always showed absolute paths and `build` and `run` relative ones.
- Thirteenth audit, three more programs (a Markdown to HTML converter, a grid game with A*, a Maven money library with JUnit tests in Axolotl):
  - The `try` error-type message reads the result type of the method it is in; a `try` in a method of a class used to get javac's own words.
  - `row[2] = '#'` on a `CharArray` or `ByteArray` suggests `CharBuffer.wrap(row).put(2, '#')` or `ByteBuffer.wrap(buf).get(i)`, and `s.toCharArray()[i]` suggests `s.charAt(i)`.
  - A pipe over an `IntStream`, `LongStream` or `DoubleStream` says to call `.boxed()` first.
  - `val n = attempt f() else e -> continue` shows how to keep the `Result` and leave with `if (r is Err) { continue }`.
  - `attempt ... else e -> throw UncheckedIOException(e)` explains that `e` is any `Exception` and suggests `IllegalStateException(e)`.
  - Error Prone's StreamResourceLeak (`attempt Files.list(dir) ...`) suggests opening the stream with `use`.
  - An unknown name or type that a class on the classpath has suggests its import: `import grid.Grid`.
  - A name that Java reserves (`new`, `default`, `int` ...) gets a suggestion such as `newValue` or `fallback`.
  - `String.format("%s-%s", words)` with a list explains that the list is one value.
  - `axl run` leaves out `.axl` files under its root whose package is not their directory, such as a vendored library in a subdirectory, whose classes used to shadow those of `-cp`; `--verbose` names them.
  - `axl check .` prints paths without `./`; `axl daemon --help` no longer lists `--no-daemon` and `--verbose`.
  - The test of the Maven archetype is `src/test/axl/.../AppTest.axl` instead of a Java file.
  - A pipe whose source has a type the translator cannot tell, such as `bag.items.stream() |> filter { ... }` or `Stream.iterate("a", { it + "a" }) |> limit(3)`, works with a stream as well as a collection; it no longer becomes `.stream().stream()`.
  - `try` passes on an error whose type is a subtype of the function's error type, such as a `BadDate` that implements the sealed `Problem` the function returns; AXL101 remains for unrelated error types.
  - A list passed where Java takes an array of a generic class, such as `Map.ofEntries(entries)` or `Map.ofEntries(List.of(Map.entry("c", 3)))`, keeps its type arguments; the error for a list that cannot be passed so no longer shows a stray `)`.
  - A branch of a `when` statement may be a bare `return` or `return value`, like `break`, `continue` and assignments.
  - A `when` over a sealed type of the classpath names the classes it does not test, and a `when` that names every constant of an enum of the classpath, such as `DayOfWeek`, needs no `else`.
- Twelfth audit, three more programs (an HTTP service with an in-process client, a command-line log tool, a concurrent batch processor with JUnit tests):
  - `attempt ... else` and `use ... else` no longer clear a thread's interrupt flag: an interrupted worker looping on `isInterrupted()` now stops after `shutdownNow()`.
  - `if (f(x) is Err)` no longer fails with "unknown type" when the type arguments of the result come from another file that this file does not import.
  - `|>` takes a `Stream` as it is (`Stream.of`, `xs.stream()`, `s.lines()`, `Files.lines/list/walk`, or a value with a written `Stream<T>` type); a stream whose type cannot be told gets a message saying to write the type.
  - `val rs = xs |> map { parse(it) }` with a file function returning `Result<T, E>` is typed as a `List<Result<T, E>>`, so `rs |> filter { it is Ok } |> map { it.value() }` compiles.
  - A list passed where a Java method takes an array of objects is passed as one, as in `CompletableFuture.allOf(futures)` and `Path.of("a", parts)`.
  - `ByteArray(n)` and `CharArray(n)` create the buffers of Java IO; as types they are Java's `byte[]` and `char[]`.
  - Diagnostics: an unknown function or type that another file of the build declares points to its import before any Kotlin-habit hint or close spelling; new or better hints for a lambda parameter Java takes for `Object` (`{ p: Person -> ... }` or `Person::city`), conversions on call results (`rnd.nextInt(10).toLong()`, `Math.round(d).intValue()`), `Int` values where a `Long` is expected (`5L`), `toString()` on a property (`String.valueOf(c.hits)`), `Ok(null)` for `Result<Void, E>` (write `Result<Void?, E>`), `flatMap` on a `Result` (use `try`); the `toDouble()` hint no longer suggests `Double.valueOf(n)`; errors about `Object` members that only follow from an unknown type are dropped.
  - Syntax hints for `try`-`finally` (use `use` with an `AutoCloseable` class), `xs[i] = v` (`xs.set(i, v)`), binary numbers `0b101`, `{ _ -> ... }`, Kotlin loop labels `outer@` / `break@outer`, chained assignments `a = b = 3`, map literals `{"a": 1}` and trailing commas.
  - Gradle prints compile errors as `file:line:column: error: message [AXLnnn]`, like the `axl` command, and a call that throws several checked exceptions gets one error; Maven's translation and `axl:check` errors end with the code too. Error Prone's LoopOverCharArray is off: `for (c in s.toCharArray())` is the way to loop over the characters of a string.
- Tenth audit, three more programs (a generic cache and repository layer, a JSON subset with generic sealed types, a concurrent pipeline) with JUnit tests in Gradle:
  - A value of a type parameter without a bound, `x: T`, `b.value()` on a `Box<T>`, `xs.get(0)` on a `List<T>`, may be null and is checked like a `String?`: `x.toString()` is an error that names `?.`, a null check and `T : Object` as the ways out, and `if (x == null)` on it no longer warns that it is never null. NullAway skips type variables, so the program used to compile and fail with a NullPointerException.
  - A class or data class implementing an interface of the build with a type argument that Java boxes, `class Ages : Repository<String, Int>` with `fn save(key: String, value: Int)`, gets `Integer` in the generated method where the interface has a type variable, so the method overrides; the Axolotl text keeps `Int`.
  - The translator types more of what it reads: the fields of data classes and classes of the build (`token.ch()`), the locals and the names an `is` test narrowed, common methods of `String`, lists and maps (`xs.subList(1, n)`, `s.length()`, `m.get(k)`), `System.nanoTime()`, `Math.max(a, b)`, `Integer.parseInt(s)`, `List.of(a, b)` and the factories of `Instant`, `Duration`, `Path` and the other JDK value types. With it a property's value tells its type in more cases (`val started = System.nanoTime()`, `val upper = label.toUpperCase()`, and a property may read the ones before it), `val items = if (xs.isEmpty()) List.of() else xs.subList(1, n)` is a `List<String>`, and `if (s is Circle) s.radius() else if (s is Square) s.side() else 0.0` no longer wraps a branch as nullable.
  - The `else` of `if (r is Err) a else r.value()` as a value knows the `Ok`, and `if (r is Ok) a else r.error()` the `Err`, as the statement form did.
  - A `when` over an enum declared in another file of the package is exhaustive without `else` when it names every constant, and a `when` over a sealed interface of another file reports the implementation it misses.
  - `==` on two `Character` values compares the characters, as it compares numbers from `?:`, `try` and `!!` by value.
  - `shows(s?.trim() ?: System.getProperty("x"))`: the fallback of the `?:` is nullable by the JDK's contract, so passing the value where null is not accepted is an error in every mode, worded as the value of the `?:`.
  - A `break` inside a `when` statement leaves the loop around it, not only the switch the `when` becomes: such a loop is labeled in the generated Java (`loop$1: for (...)`) and the `break` names it. Before, the program went on with the next element and Error Prone warned about an unnecessary break in the switch.
  - A branch of a `when` statement may be `break`, `continue` or an assignment without braces: `2 -> continue`, `else -> n += x`. In a `when` that gives a value they stay errors; a jump gets the message about leaving the `when` on its own. IntelliJ reads such an arm as the compiler does, so the `when` parses, folds, resolves and completes like one with braces; the language server already did, through the compiler's parser.
  - The handler of an `attempt` on a line of its own can also be `break`, `continue`, `return` or `return value`, so a retry loop around a call that returns nothing can leave the loop or the function when the call fails: `attempt send(i) else e -> continue`. The same rules as for `break`, `continue` and `return` elsewhere apply (not in a lambda for `break` and `continue`, not out of a `when` that gives a value). The language server and IntelliJ read the new handlers; `val r = attempt f() else break` is explained as an error.
  - A class that implements a Java generic interface can write `Int` where the interface has a type variable: `class Doubler : Function<Int, Int> { fn apply(x: Int) -> Int = x * 2 }`, and likewise `Supplier<Int>`, `Predicate<Int>`, `Iterator<Int>` and `Comparator<Int>`. The generated method takes and returns `Integer` there. This works for the interfaces of the JDK; for an interface from a jar or from your own Java sources, write `Integer`.
  - A property can be initialized by a call to a method of its own class that has a written result type: `val total = compute()` with `fn compute() -> Int`.
  - Hints for more Java and Kotlin habits: `x ?: break` and `x ?: continue` get the hint that `?: return` has; `^`, `~`, `>>` and `and`/`or`/`xor`/`shl`/`shr` on numbers say that Axolotl has no bit operators and name `BitSet` or a small Java class; `while` and `for` without braces say so instead of listing tokens; `is List<*> ->` in a `when` branch says to write `is List`; a `val` in a data class body says to write a method; a wildcard import shows the example for the imported package.
  - Better wording for `use`, pipes and calls: `return`, `break` or `continue` as the `else` of `use` is explained (the `else` is a call or a `throw`, and the failure becomes a value through a function and `attempt`); an unknown pipe stage such as `sum` points to the stream; a pipe over an array suggests `List.of`; wrong-argument messages print lambdas as `{ w -> ... }` and suggest a `Supplier` lambda where a value was given.
  - Better wording for types and nulls: a `Character` is no longer called a primitive value; `return 5` in `fn main` and `axl run` of a `main` with a result type point to `System.exit(code)`; a lambda-argument interface created inside another constructor call gets the lambda hint; an unbounded type parameter value gets the `T : Object` advice; a non-Boolean `try` next to `&&` or `||` gets the operand wording; the `try attempt` error-type message names the written `Result` type that fixes it.
  - A generic sealed type from a jar or class directory on the classpath is now narrowed by `is`, `when` and an early exit with its type arguments (`if (o is Some) o.value()` on an `Option<String>` is a `String`), and a generic record from the classpath is taken apart the same way; a class implementing a generic interface that is not in the JDK (from a jar, a class directory or a `.java` file of the build) writes `Int` in its methods like for the JDK. The translator reads the compiled classes through javac.
  - IntelliJ translates a file with the declarations of the other `.axl` files of its source roots (for a test file also the production roots of the modules it depends on), as the build and the language server do: the Java view of a class implementing an interface of another file has the accessors of the interface's values, a generic sealed type of another file is narrowed with its type arguments, and the errors while typing and Show Generated Java follow. The other files are parsed again only when they change.
  - A `when` that gives a value reports a branch that ends with an `if` statement holding its values at the `if`, with the hint to write `if (c) a else b` without braces, as a returned `when` does; in both forms the values inside that `if` no longer draw "this expression does nothing on its own".
  - `break` and `continue` inside an `if` whose condition has `try` on the right of `&&` or `||` leave the loop: such an `if` is a `do { ... } while (false)` in the generated Java, which a plain jump left instead of the loop, so the loop is labeled as for a `when`.
  - `use (val br = BufferedReader(...)) { ... }` without `else`, where only `close()` throws, says "`close()` of `BufferedReader` can throw `IOException`; add `else e -> ...` to the `use`" instead of javac's words.
  - A type test on a call, `first.value() is Punct && first.value().ch()`, says that `is` does not narrow a call and to copy it to a `val` first, `val value = first.value()`, and test that, or to name the subject of a `when`; before, it called the value a `var` or named the method missing on the wider type.
  - `Sorted<Object>()` for `class Sorted<T : Comparable<T>>` says "`Object` does not fit `T : Comparable<T>` of `Sorted`; use a type that is `Comparable`, such as `String` or `Int`", with the bound read from the class of the file, of another file of the build or of the JDK.
  - `Level.LOW` with `Level` an enum of another file of the package that is not imported gets the import to add, as the type position does, once per file: "unknown name `Level` (and 2 more uses in this file); it is declared in app/Model.axl: `import app.Model.Level`", at the name.
  - The warning about `equals` on types that differ only in their type arguments names the two types: "`None<Int>` and `None<String>` are never equal: the type arguments differ".
  - The hint for `attempt` around a call that returns nothing shows the call as written, `attempt b.submit(Item("a")) else e -> ...`, not the Java it became.
  - `attempt q.poll(...) else e -> null` says that the `else` of `attempt` gives the error of the `Result` and cannot be `null`: give it the exception, `else e -> e`, or write the error type with `?`; an error type written with `?`, `Result<String?, Exception?>`, now takes `null` there.
  - `while ((line = readLine()) != null)` says that an assignment is a statement, not a value: write `line = readLine()` on its own line, then `while (line != null)`.
  - `results |> filter { it is Ok } |> map { it.value() }` on a `List<Result<Int, String>>`: the `Ok`s keep their type arguments after the `filter`, as `Some<String>` does on a `List<Option<String>>`; before, `Ok` and `Err` were left raw and the next stage saw a `Result`.
  - After `if (o is None) { return }` on a sealed interface of the build with two implementations, `o` is the other one, `o.value()` of a `Some`, as `r.value()` is after `if (r is Err) { return }`; the `else` of `if (o is None) a else o.value()` knows it too.
  - A type parameter of a method named like one of its class, `class Box<T> { fn map<T>(...) }`, is an error at the declaration that says the method can use the class's `T` as it is; before, javac said "expected `T`, found `T`".
  - `val nums: List<Token> = tokens |> filter { it is Num }` keeps the elements `Token`s, as the written type says, instead of narrowing them to `Num` and failing on the invariant list; without a written type the list is a `List<Num>` as before.
  - Strict null mode no longer suspects `type.cast(job)` on a `Class<T>`: a JDK method that returns a type variable of its receiver gives what went in, also when the type argument is a type variable itself.
  - No warning about `Integer` in the methods of a class that implements a Java interface such as `Supplier<Int>`, where Java needs the box, and a method named like a Java keyword gets the same error as a function.
  - `is Ok` and `is Err` inside an `if` body no longer crash Error Prone: its AlreadyChecked check, which cannot read the pattern variables, is off, and the Gradle and Maven builds run the same checks as `axl build`.
  - `Runnable({ -> ... })` and `Supplier<String>({ -> "x" })` no longer crash the null checker; the error says to pass the lambda where the interface is expected or to keep it in a value of that type, and a crash of the checker is reported as an internal error of the file, not as a stack trace.
  - A method with `Int` in a class that implements a Java generic interface with `Integer` there, such as `fn get() -> Int` for `Supplier<Int>`, gets the signature to write in place of javac's "does not override abstract method" and "return type int is not compatible with java.lang.Integer".
  - `fn main(args: Array<String>)` and `fn main(args: String[])` say that `fn main()` already has `args`, read as `List.of(args)`; `abstract class` says that an interface takes the role of an abstract class.
  - System.`in` says that backticks do not quote names, `import java.util.List as JList` that imports have no alias, `o is List<*>` that a type test names the class only, `synchronized`, `volatile` and `outer:` what to use instead, `x.split(":")[0]` gives `List.of(x.split(":")).get(0)`, `Function.identity<String>()` says to give the type to the value, and `import jobs.Job.Job` that `jobs.Job` is the type of the file.
  - `x.toInt()`, `(Int) x` and a `Double` stored in an `Int` offer `Double.valueOf(x).intValue()`, which drops the fraction, next to `Math.toIntExact(Math.round(x))`, which rounds; `3.7.toInt()` shows the whole literal.
  - `a + b` on `BigDecimal` values and `"a" < "b"` name the method to call: `a.add(b)`, `a.compareTo(b) < 0`.
  - The first warning of each Error Prone check in a build names the check and the `@SuppressWarnings` that turns it off.
  - `c.by()` on `class Inc(val by: Int)` says that `by` is a property, read as `c.by`; `r.value()` on a `Result` that no test narrowed says to test it with `is Ok` first or to write `try r`.
  - An unknown type used several times in a file is reported once, at its first use, with the number of the other uses.
  - Strict null mode trusts `ExecutorService.submit`, the `schedule` methods of `ScheduledExecutorService`, `ForkJoinPool` and the locks of `java.util.concurrent`.
  - `docs/migration.md` describes what Axolotl has now, and what stays in Java.
- Eighth audit:
  - javac's "incompatible types" is explained as an impossible `is` test only where javac points at the type test; elsewhere it is a type mismatch.
  - `attempt` around a call that returns nothing, also with `else e -> throw`, says that it is a statement, and the exception inside the call is not reported a second time.
  - A call that always fails, such as `LocalDate.parse("2026-13-40")`, is a warning inside `attempt` and an error elsewhere, in Axolotl's words.
  - `try` on a `Result` whose value type javac could not name, as from `Constructor<*>.newInstance`, names what was written and the type to write, not the runtime's `unwrap()`.
  - `attempt ... else e -> e.getMessage()` returned as a `Result<T, String>` gets one message, about the error that may be null and its `?:` fallback.
  - Strict null mode trusts the JDK methods that never return null: collection views and factories, `Arrays`, `Collections`, `Optional`, `Enum.name`, `Object.getClass`, `Files`, `Path`, `java.time`, `java.text`, `java.math`, the `java.io` readers and writers and the reflection getters that always have a value.
  - `for ((k, v) in m.entrySet())` is explained as destructuring, `parts[0]` on an array suggests `List.of(parts).get(0)`, casts to number types and `n.toDouble()` say how to convert numbers, and `"""raw"""` is explained as a raw string once.
- Stack traces: after `axl build`, Gradle `compileJava` or Maven `axl:smap`, the class files name the `.axl` file in `SourceFile` and carry `.axl` lines in the `LineNumberTable`, so plain JVM traces, logs and debuggers show `at app.Main.main(Main.axl:7)` without a retrace step. Generated lines without a mapping take the closest mapped line above them. The SMAP stays (the `Axolotl` stratum maps each `.axl` line to itself) and the existing switch (`--no-smap`, `axl { smap = false }`, `<smap>false</smap>`) leaves the Java lines alone. `axl retrace` still rewrites old traces and traces of classes built without the step, and leaves frames that name `.axl` files as they are. See `docs/debugging.md`.
- Language: annotations (`@Test fn ...`, `@Marker(x = 1) data class ...`), which go into the generated Java as they are, so JUnit tests, Jackson and JPA records and Spring entry points can be written in Axolotl; class literals `String.class` (`Int.class` is `Integer.class`); the type argument `*` for Java's `?`. `@Nullable` is rejected in favor of `T?`.
- Language: `attempt X else e -> throw Y` gives the value of `X` and throws `Y` when it fails, for checked exceptions where no `Result` is wanted.
- `is Ok` and `is Err` take the record apart in the generated Java, `x instanceof Ok(var x$1)`, so a `Result` whose type the translator does not know (an `attempt` around a Java call, a function of another file, a method of a class) is narrowed correctly instead of reading its value as `Object`.
- Language: `attempt` on a line of its own runs a call that returns nothing and handles its failure, `attempt conn.commit() else e -> throw IllegalStateException(e)`, so Java methods such as `commit()`, `close()` and `Thread.sleep` that declare a checked exception can be called from Axolotl.
- Language: `x ?: throw Y` throws when `x` is null and otherwise gives `x` without the `?`.
- `Result` has `isOk()`, `map` and `getOrElse` next to `isErr()` and `mapErr`.
- Pipes: `filter { it is Circle }` narrows the elements to `Circle` for the rest of the pipe and for the list it builds, and a `map` that gives null may be followed by `filter { it != null }`.
- Language: after `if (r is Err) { return }` a `Result` is known to be an `Ok` (`r.value()`), and after `if (r is Ok) { return }` an `Err` (`r.error()`).
- Language: type parameters on data classes, classes, interfaces and functions: `data class Pair<A, B>(...)`, `interface Repository<K, V>`, `fn largest<T : Comparable<T>>(xs: List<T>) -> T?`. A parameter without a bound takes nullable types too; `T : Object` takes the types without null. A type test on a value of a generic sealed interface keeps the type arguments.
- Language: `use (val reader = open()) { ... } else e -> handler` closes a resource after the block, also when it throws.
- Language: a data class may have a body with methods, for the interfaces it implements: `data class Money(val cents: Long) : Comparable<Money> { fn compareTo(o: Money) -> Int = ... }`.
- Language: enum constants carry fields, `enum Planet(val mass: Double) { EARTH(5.976e24) }`, read as `Planet.EARTH.mass`.
- Language: a class extends a Java class by calling its constructor, `class NotFound(val id: String) : RuntimeException("not found: " + id)`, `class Worker : Thread("w") { fn run() { ... } }`; a class written in Axolotl still cannot be extended.
- Language: `break` and `continue` in `while` and `for` loops.
- Language: `xs |> first ?: fallback` gives the fallback for an empty pipe.
- The translator reads the declarations of the other `.axl` files of the build: a type test on a generic data class declared in another file keeps its type arguments or takes the record apart, and the result type of a function of another file is known to the translator.
- Language: an interface requires methods as well as values: `fn add(text: String) -> Int` without a body, which each class that implements the interface writes. The checker compares the parameter and result types, including nullness, and explains a method with a body or in a data class.
- `axl run` takes `-cp` like `axl check` and `axl build`, with the jars on the classpath both when the program is compiled and when it runs, so a program with a library runs without a build tool. The build documentation names the runtime jar that `java -cp` needs after `axl build`.
- Editors: annotations are parsed and colored in IntelliJ, VS Code and the language server, and completed after `@`; IntelliJ's Java view of an `.axl` file carries the annotations, a class with `@Test` methods runs as a JUnit configuration from the gutter, and *Convert Java file to Axolotl* keeps annotations and writes `X.class` and `List<*>`.
- Editors: interface methods (`fn add(text: String) -> Int` without a body), `x ?: throw Y` and `attempt call() else e -> throw Y` as a statement are understood by the IntelliJ parser, Java view, completion and Convert Java file to Axolotl (abstract interface methods with parameters or no result become `fn` signatures) and by the language server (hover lists the members of an interface, `method` semantic tokens, member completion with Axolotl result types).
- Editors: type parameters and `xs |> first ?: fallback` in IntelliJ (the parser, the Java view with `Pair<A, B>` and `static <T extends Comparable<T>> T largest(List<T> xs)`, the types of generic values and calls, and go to declaration, find usages, rename, completion and a color of their own for a `T` inside its declaration; *Convert Java file to Axolotl* writes `class Box<T>`, `fn f<T>(...)` and `T : Comparable<T>`) and in the language server (hovers with `<T>`, completion of the type parameters in scope, `typeParameter` semantic tokens, the members of a `T` value from its bound).
- Editors: `use (val r = ...) { ... } else e -> ...`, `break`, `continue`, enum fields (`enum Planet(val mass: Double)`), classes that extend a Java class (`class Worker : Thread("w")`), data class bodies with methods and properties without a written type are understood by IntelliJ (the parser, the Java view with the enum's fields and constructor, the extended class and the record's methods, resolve and completion of enum fields, data class methods, inherited members and `use` locals, keyword highlighting of `break`, `continue` and `use` before `(val`) and by the language server (symbols, outline, hover, go to definition, completion with `print` and `readLine`, keywords, `parameter` tokens for the error name of a handler) and the VS Code grammar; *Convert Java file to Axolotl* writes `enum X(val f: T) { A(...) }`, `: Base(args)` from a `super(...)` call, data classes with a body, `use ... else e -> ...` for a `try` with one resource and one `catch`, and `break`/`continue` without labels.
