# The Axolotl language

Axolotl translates to readable Java 21. Every `.axl` file becomes one Java class; functions become
static methods. This page describes the language; the exact Java for each construct is listed in
[the expansion table](expansion.md).

## Files

```axl
package util                      // required in every file
import java.util.List             // a type
import fn util.Strings.shout      // a function (static method)
import val util.Config.limit      // a value (static field)

fn twice(n: Int) -> Int = n * 2   // expression body
private fn log(text: String) {    // no result type: void
    println(text)
}

val greeting = "hi"               // top-level value
val limit: Int = 10               // with a written type
```

The type of a top-level `val` may be left out. Literals, constructor calls and functions of the same
file are typed directly; anything else (a Java method, a function of another `.axl` file) is typed by
javac from a draft translation of the module. When no type can be found, AXL009 asks for one.

`Parse.axl` becomes `@NullMarked public final class Parse`. Top-level declarations are public
unless marked `private`. `fn main()` becomes `public static void main(String[] args)`.

## Types

`Int`, `Long`, `Boolean`, `Double` are Java's primitives; inside generics and when nullable they are
the wrapper classes. Every other name is a Java type: `String`, `List<Int>`, `java.util.Map<String, Int>`.
`T?` allows `null`; `T` does not. Nullness is checked by NullAway in JSpecify mode.

## Statements

- `val x = e` declares a final local; `var x = e` a mutable one. A type is optional, except when the
  value is `null` or a lambda: `val x: String? = null`.
- `x = e`, `x += e`, `-=`, `*=`, `/=`, `%=` assign to a `var` (or to a Java field).
- `if (c) { ... } else if (d) { ... } else { ... }`, `while (c) { ... }`, `return e`.
- Statements end at the end of a line or at `;`. A line starting with `(`, `+` or `-` starts a new
  statement; break long lines after an operator or before `.`, `?.`, `?:` and `|>`. Two statements
  on one line need a `;` between them, and an expression that does nothing, such as `x + 1`,
  cannot stand as a statement.
- Local names cannot shadow other local names.

## Expressions

Calls, member access, `+ - * / %`, comparisons, `== !=` (Java's `==`), `&& || !`, string
concatenation, lambdas and method references `String::length` work as in Java. A call of a
capitalized name creates an object: `StringBuilder("x")`, `ArrayList<String>()`.

Lambdas are written in braces: `{ x -> x + 1 }`, `{ a, b -> a.compareTo(b) }`. Without an arrow,
`it` is the parameter if the body uses it: `{ it > 0 }`; `{ -> run() }` takes no parameters. Inside
a lambda that has `it`, a nested lambda without an arrow reads that `it`; otherwise `it` belongs to
the innermost lambda that uses it. A lambda with several statements returns its value with `return`.

## Null safety

```axl
fn greet(name: String?) -> String = "Hello, " + (name?.trim() ?: "stranger")
```

- `a?.m()` calls `m` only when `a` is not null; otherwise the whole rest of the chain is skipped and
  the value is `null`. As a statement, `listener?.onEvent(e)` works with void methods.
- `a ?: b` is `a` unless it is null, then `b`; `b` is evaluated only when needed.
- `a!!` is `a` when it is not null and throws `NullPointerException` otherwise, at the `!!`.
- After `if (x != null)`, an early `return` or inside `&&`/`||`, a local or parameter is known to be
  non-null. A nullable field is copied to a local `val` first.
- `?` in a type argument counts too: `get` on a `List<String?>` returns `String?`, the `it` of a pipe
  over it may be null, `sorted` needs elements that are not null, and `|> first` gives `null` for a
  null element as for an empty pipe. `add` on an `ArrayList<String>` does not accept null.
- A local written with a type without `?` cannot hold a value that may be null, and a lambda passed
  to Java cannot return null where the interface's result type has no `?`: `Supplier<String>` must
  not return null, `Supplier<String?>` may.

### Java code without nullness annotations

By default a value returned by Java code without JSpecify (or `@NonNull`/`@Nullable`) annotations is
trusted to be non-null, as NullAway does. Strict null mode (`axl { strictNull = true }`,
`-Daxl.strictNull=true`, `axl build --strict-null`) treats such values as `T?` instead:

```axl
val home = System.getenv("HOME")
println(home.length())              // AXL104: `home` may be null
println(home?.length() ?: 0)        // fine
println(System.getenv("HOME")!!.length())   // fine: the check is explicit
```

Such a value may not be dereferenced, unboxed, passed to an Axolotl parameter, returned from an
Axolotl function or stored where the type is written without `?` until it is checked. A top-level
`val` inferred from it gets a nullable type, a lambda that unannotated Java code calls gets nullable
parameters, and a reference to an Axolotl function with parameters without `?` cannot be passed to
such code.

Trusted in strict mode: static final fields of the JDK such as `System.out`, constants, the Axolotl
runtime, `Objects.requireNonNull`, the methods of `String`, `StringBuilder`, `Math` and the number
classes (except `Integer.getInteger` and `Long.getLong`), `List.of`, `Set.of`, `Map.of`, `toString()`,
the stream operations that pipes expand to and code in a `@NullMarked` scope.

## Errors as values

`Result<T, E>` is either `Ok(value)` or `Err(error)`.

```axl
fn parse(s: String) -> Result<Int, String> =
    attempt Integer.parseInt(s) else e -> "not a number: " + s

fn doubled(s: String) -> Result<Int, String> {
    val n = try parse(s)          // on Err, return it from doubled
    return Ok(n * 2)
}
```

- `attempt X else e -> Y` runs `X`; an `Exception` becomes `Err(Y)`. Without a name the exception
  is not available: `attempt X else Y`.
- `try r` unwraps an `Ok`, or returns the `Err` from the enclosing function, which must return
  `Result`. The error types must match; convert them first with `mapErr`:
  `try parse(s).mapErr({ code -> "error " + code })`.
- `try` binds like a unary operator and works anywhere in an expression:
  `Ok(try a() + try b())`, `if (try ready()) { ... }`, `while (try more()) { ... }`.
  Operands to its left are evaluated first, in order. `&&`, `||` and `?:` evaluate a `try` on their
  right side only when they need it.
- `try` is not allowed inside a lambda or `attempt` (it would return from the lambda), after `?.`
  and in `return try e` (write `return e`).

## Pipes

`|>` feeds a collection through Java stream operations:

```axl
val evens = numbers |> filter { it % 2 == 0 } |> map { it * 10 }
val firstThree = names |> sorted |> distinct |> limit(3)
val count = numbers |> filter { it > 0 } |> count
numbers |> forEach { println(it) }
```

| Stage | Meaning |
|-------|---------|
| `filter { }`, `map { }`, `peek { }` | Stream operations with one element `it`. |
| `sorted`, `sorted { a, b -> ... }` | Natural order or a comparator. |
| `distinct`, `limit(n)`, `skip(n)` | As in `Stream`. |
| `forEach { }`, `anyMatch { }`, `allMatch { }`, `noneMatch { }`, `count`, `first` | End the pipe; `first` is the first element or `null`. |
| `mapResult { }` | Maps every element to a `Result`; the first `Err` is returned from the function. |

Without an ending stage the result is a `List`. `mapResult` needs a function that returns `Result`
and the element type of the list it builds: either the lambda calls a function of the same file
that returns `Result<T, E>`, or the result initializes `val name: List<T> = ...`.

## Interoperability

Java code in the same module sees `.axl` files as classes: `util.Parse.doubled("21")`, and works
with `Result` through `switch` with record patterns:

```java
String msg = switch (util.Parse.doubled("x")) {
    case Ok<Integer, String>(var v) -> "ok " + v;
    case Err<Integer, String>(var e) -> "fail " + e;
};
```
