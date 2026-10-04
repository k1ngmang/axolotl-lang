# Migrating from Java

Axolotl and Java live in the same module and call each other directly, so a codebase moves one file
at a time. Nothing has to be converted before it is useful.

## Steps

1. Apply the [Gradle](gradle.md) or [Maven](maven.md) plugin. Existing Java code keeps compiling;
   NullAway checks only code inside `@NullMarked` scopes, so unannotated Java is not affected.
2. Write new code in `src/main/axl`. Java calls it as `Parse.doubled("21")`; it calls Java as is.
3. Convert utility classes with static methods, records, enums, sealed interfaces and simple classes.
   In IntelliJ IDEA the intention *Convert Java file to Axolotl* does the mechanical part; check the
   result for `T?` where values may be null.
4. Mark the Java code that Axolotl calls with JSpecify `@NullMarked` and `@Nullable`, or turn on
   [strict null mode](language.md#java-code-without-nullness-annotations), so that nulls coming
   from Java are checked too.

## What maps to what

| Java | Axolotl |
|------|---------|
| `public final class Util { private Util() {} ... }` | the file `Util.axl` |
| `public static int twice(int n) { return n * 2; }` | `fn twice(n: Int) -> Int = n * 2` |
| `private static void log(String s)` | `private fn log(s: String)` |
| `public static final int LIMIT = 10;` | `val LIMIT = 10` |
| `final var x = f();` / `var x = f();` | `val x = f()` / `var x = f()` |
| `@Nullable String name` | `name: String?` |
| `name != null ? name : "none"` | `name ?: "none"` |
| `user == null ? null : user.getName()` | `user?.getName()` |
| `Objects.requireNonNull(x)` | `x!!` |
| `new StringBuilder("x")` | `StringBuilder("x")` |
| `System.out.println(x)` | `println(x)` |
| `x -> x + 1`, `() -> run()` | `{ x -> x + 1 }`, `{ -> run() }` |
| `list.stream().filter(x -> x > 0).toList()` | `list \|> filter { it > 0 }` |
| `import static a.B.f;` | `import fn a.B.f` |
| `@Override public String toString()` | `@Override fn toString() -> String` |
| `Class<?>`, `List<?>` | `Class<*>`, `List<*>` |
| `String.class` | `String.class` (`Int.class` is `Integer.class`) |
| `try { return Integer.parseInt(s); } catch (NumberFormatException e) { ... }` | `attempt Integer.parseInt(s) else e -> ...` returning `Result` |
| a method that throws a checked exception to report failure | a function returning `Result<T, E>`; callers use `try` |

## What stays in Java

Axolotl has functions and values, classes with properties and methods, data classes, enums, sealed
interfaces with values and methods, and type parameters on all of them. Keep these in Java and call
them from Axolotl:

- extending a class written in Axolotl: a class extends a Java class, `class NotFound(val id: String) :
  RuntimeException(...)`, but Axolotl classes stay final, and an interface with methods takes the role
  of an abstract class;
- annotation types;
- code that needs arrays, casts, `switch`, `synchronized` or labels. Java methods that take an array of objects accept a list,
  `ByteArray(n)` and `CharArray(n)` cover IO buffers, Axolotl reads a Java array as `List.of(array)`, converts numbers with methods such as `Math.toIntExact(Math.round(x))`, chooses
  with `when`, and locks with the classes of `java.util.concurrent`.

Framework entry points work in Axolotl: a `class` with annotated methods is a JUnit test class, a
Spring controller or a JAX-RS resource, and an annotated `data class` is a Jackson or JPA record.

Most loops over collections become pipes; a counting `for (i in 0 until n)` or a `while` loop covers the rest.

## Exceptions and `Result`

Java code that calls an Axolotl function returning `Result` gets an `Ok` or an `Err` record and can
`switch` over it with record patterns. Going the other way, `attempt` turns any `Exception` thrown
by Java into an `Err`. A good boundary is to convert exceptions to `Result` where Axolotl calls an
I/O or parsing API, and to keep exceptions for bugs.
