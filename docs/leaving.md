# Leaving Axolotl

Axolotl is designed so that you can stop using it at any time and keep your code. The translator
writes ordinary Java 21 that a person could have written, and that Java is what gets compiled,
tested and shipped.

## How to leave

1. Build once, so that the generated sources are up to date:
   `build/generated/axl/<sourceSet>` with Gradle, `target/generated-sources/axl` with Maven.
2. Copy the generated `.java` files into `src/main/java` (and `src/test/java` for test sources),
   keeping their package directories. Delete the `.axlmap` files.
3. Delete `src/main/axl` and `src/test/axl` and remove the Axolotl plugin from the build.
4. Keep the dependency on `org.axl:axl-runtime`, or copy its five classes (`Result`, `Ok`, `Err`,
   `ThrowingSupplier`, `Nulls` in `axl.lang`) into your project under the same or another package.
5. Keep Error Prone and NullAway if you want the null checks to continue; the generated classes are
   already `@NullMarked` and annotated with JSpecify.

## What the code looks like

Generated code follows the [expansion table](expansion.md). A few names may look unusual to a
reader:

- temporaries start with `$`: `$t1` for `try`, `$n1` for `?.`, `$c1` for copies of captured
  variables, `$p1` for `mapResult`. Rename them as you like.
- `a ?: b` is `Nulls.or(a, () -> b)` and `a?.m()` is `Nulls.map(a, x -> x.m())`; both can be
  rewritten as plain conditionals.
- `try r` is three statements: a temporary, an `if` that returns the error, and `Result.unwrap`.

The output is deterministic, so a diff of the generated sources between two commits shows exactly
what changed.
