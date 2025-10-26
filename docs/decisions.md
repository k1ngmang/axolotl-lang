# Decisions

One line per decision where the specification is silent or had to be adjusted.

- Comments in the runtime sources are translated to English; the code is otherwise verbatim from the specification.
- Statements end at a newline or `;`. A call `(` and a binary `+`/`-` must be on the same line as the token before them, otherwise they start a new statement.
- Operator precedence follows Kotlin: `?:` binds tighter than comparisons and looser than `+`/`-`. `?:` is right-associative, so `a ?: b ?: c` evaluates `c` only when `a` and `b` are null.
- `==` and `!=` are emitted as Java `==` and `!=` (reference equality for objects), keeping the translation one to one.
- An unqualified call of a capitalized name that is not a declared or imported function is a constructor call: `Foo(x)` becomes `new Foo(x)`. Type arguments are written explicitly: `Foo<String>()` or `Foo<>()`. Only `Ok` and `Err` get the diamond automatically.
- `?.` wraps the rest of its call chain: `a?.b().c()` becomes `Nulls.map(a, $n1 -> $n1.b().c())`, so the first null skips the whole tail.
- A safe call chain used as a statement expands to `final var $n1 = a; if ($n1 != null) { ... }` instead of `Nulls.map`, so `listener?.onEvent(x)` works with void methods.
- Lambdas are written `{ x, y -> body }`. `{ body }` takes no parameters, unless the body uses `it` and no `it` is in scope; then `it` is its parameter. `{ -> body }` always takes no parameters. A lambda with several statements returns its value with an explicit `return`.
- `var` declares a mutable local (`var x = 0` becomes `var x = 0;`); `=`, `+=`, `-=`, `*=`, `/=` and `%=` are assignment statements. Parameters and `val`s cannot be assigned (AXL008).
- Copies of captured `var`s are named `$c1`, `$c2`, ... A `while` whose condition needs copies becomes `while (true) { copies; if (!(cond)) break; ... }`; an `else if` that needs copies becomes `else { copies; if ... }`.
- `fn f() = try g()` in a function without a result type is the statement form of `try`; in a function with a result type it is `return try` (AXL005). `try` in a top-level `val` is AXL007.
- `private val` is allowed at top level and becomes `private static final`.
- Java keywords cannot be used as names (AXL011); the file name must be a valid Java class name (AXL012).
- `fn main()` means a function named `main` with no parameters and no result type.
- javac diagnostics are reported at the first non-blank column of the mapped .axl line; multi-line javac messages are joined with `; `.
- Generated classes have no blank lines between members, as in the specification example.
- `axl build` writes generated sources to the `-d` directory unless `-s` is given, like javac. `axl run` compiles the whole source root, which it derives from the file path and its package.
- NullAway runs with `JSpecifyMode=true` and `OnlyNullMarked=true`, which replaces `AnnotatedPackages`; every generated class is `@NullMarked`.
- Error Prone runs inside the compiler's JVM, so the CLI and the tests start with `--add-exports`/`--add-opens` for `jdk.compiler`; `--no-null-checks` turns the checks off.
- NullAway messages other than dereferences are shown without the `[NullAway]` prefix and link; dereferences get their own message (AXL102).
- `--inline-elvis` is implemented although the benchmark shows no measurable cost (see `docs/perf.md`), because the build plugins expose it. It expands only a `?:` at the root of an initializer, assignment or `return`, and the temporary shares the `$t` counter.
- The Gradle task is named `generateAxl` (`generateTestAxl` for tests) instead of `generateBrod`.
- Errors of `compileJava` are moved to `.axl` files by a javac plugin (`-Xplugin:AxlDiagnostics` from `axl-compiler-javac`) rather than by intercepting task output; see `docs/gradle.md`.
- Error Prone and NullAway are runtime dependencies of the CLI only, so that `axl-compiler-javac` on an annotation processor path does not bring a second copy of them.
- Functional tests of build plugins resolve Axolotl artifacts from `build/test-repo`, which every published module also publishes to.
- `axl-maven-plugin` and `axl-archetype-quickstart` are Maven projects (annotations, invoker and archetype tooling are Maven-native); the Gradle build runs them with the Maven wrapper in `axl-maven-plugin/` as part of `check`.
- Maven shows `.axl` positions during `compile` through the same javac plugin as Gradle; `axl:generate` passes its arguments in the property `axl.javacPlugin`. `axl:check` is a standalone goal that compiles again and prints `.axl` positions, since a goal cannot run after a failed `compile` in the same build.
- Error Prone is loaded from the class loader of `axl-compiler-javac` when it is a `URLClassLoader` (a Maven plugin realm), otherwise from `java.class.path`.
- Parentheses around `?:`, `?.` and `attempt` are dropped in the generated Java, where they expand to method calls; Error Prone would flag them as unnecessary.
- The SMAP's default stratum is `Java`, not `Axolotl`, so debuggers without Axolotl support keep showing the generated `.java` lines.
- In Gradle the SMAP is added by a last action of `compileJava` rather than by a separate task, so the classes stay outputs of one task and are cached with their SMAP.
- Failing tests are retraced by a JUnit Jupiter extension (`TestExecutionExceptionHandler`) loaded through extension autodetection, not by a `TestExecutionListener`: JUnit notifies listeners of finished tests in reverse order, so Gradle's and Surefire's own listeners would record the failure first. The same extension works for Maven, instead of a Surefire reporter.
- Retraced stack frames drop the class loader name, which a new `StackTraceElement` would otherwise print even for the built-in `app` loader.
- The language server uses full document synchronization and serves every request on one thread; while a line is incomplete, completion and navigation use the last version of the file that parsed.
- Member completion knows the receiver's type when it is a class name, a name with a written type, or a local whose type javac infers in the generated code; longer call chains are not completed.
- The VS Code extension bundles the server's jars (`server/lib`) and starts it with the JDK from `axl.java.home`, `JAVA_HOME` or the PATH.
