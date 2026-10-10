# Stability

Axolotl is at 0.x. This page says what code written today can rely on, what may still change before 1.0, and how a
change will reach you.

## What is stable

These parts have been used by every program of the audits, by the compiler itself and by ports of real Java
libraries, and they will not change in a way that breaks code that compiles today:

- Files, packages and imports, including `import fn` and `import val`.
- `val`, `var`, functions with block and expression bodies, type parameters with bounds, lambdas and `it`.
- Data classes, classes with parameters, `private` parameters and delegating constructors, interfaces, sealed
  interfaces (also across the files of a package) and enums.
- `if` and `when` as statements and values, `is` and narrowing, `for` over collections and counting `for`, `while`,
  `break`, `continue` and `return`.
- Null safety: `T?`, `?.`, `?:`, `!!`, narrowing after a null check, and what a value from Java is taken to be.
- Errors as values: `Result`, `Ok`, `Err`, `try`, `attempt` (with and without a typed handler) and `use`.
- Pipes and their stages.
- The Java a declaration becomes as seen from Java code: class, record, interface and enum names, method and
  constructor signatures, and the `axl.lang` runtime classes they use (`Result`, `Ok`, `Err`, `Nulls`).
- Error codes (`AXL001` ... `AXL104`) and what each one is about.
- The options of `axl`, the Gradle plugin and the Maven plugin.

## What may still change before 1.0

- New constructs may be added. They are added only when a real program needed them, and they do not change the
  meaning of code that compiles today.
- The wording of messages and hints changes often; tools should read the error code, not the text.
- Code that compiles today with a warning may become an error when the warning marks a real bug.
- The Java the compiler writes inside a method body, the helper methods of `Nulls` that it calls and the names of
  its temporaries (`$t1`, `loop$1`) are not an interface.
- The formatter's layout may change in small ways; `axl fmt --check` in CI may then ask for one more run.

## How a change reaches you

- Every change is listed in [CHANGELOG.md](https://github.com/succubus-uwu/axolotl/blob/main/CHANGELOG.md) under
  its version.
- A construct that is going away first gives a warning that names its replacement, for at least one minor version,
  and `axl fmt` or the hint shows the rewrite where it can.
- A change in the Java seen from Java code (a signature, a runtime class) is called out in the changelog as such.

## What 1.0 will mean

1.0 freezes the language: after it, code that compiles keeps compiling with the same meaning in every 1.x release.
It comes when ports of new real libraries stop needing changes to the language, the release workflow publishes the
artifacts, and the language has users besides its authors.
