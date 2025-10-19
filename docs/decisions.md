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
