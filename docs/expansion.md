# Expansion rules

How every construct is translated to Java. The generated code is deterministic: the same input
always gives byte-for-byte the same output.

| Axolotl | Java |
|---------|------|
| Any file | `package p;`, then `import axl.lang.*;` and `import org.jspecify.annotations.*;`, then the file's imports. |
| `Parse.axl` | `@NullMarked public final class Parse { private Parse() {} ... }` |
| `fn` / `val` at top level | `public static` (`private static` with `private`); `val limit: Int = 10` is `public static final int limit = 10;` |
| `val names = List.of("a")` at top level | the inferred type is written out: `public static final List<String> names = List.of("a");` |
| `import a.b.C` | `import a.b.C;` |
| `import fn a.B.f`, `import val a.B.v` | `import static a.B.f;`, `import static a.B.v;` |
| `fn main()` | `public static void main(String[] args)` |
| `Int`, `Long`, `Boolean`, `Double` | `int`, `long`, `boolean`, `double`; `Integer`, ... inside generics |
| `Int?` | `@Nullable Integer` |
| `T?` | `@Nullable T` (for a qualified name: `java.util.@Nullable List`) |
| `val x = e` / `var x = e` | `final var x = e;` / `var x = e;` |
| `val x: String? = null` | `final @Nullable String x = null;` |
| `a ?: b` | `Nulls.or(a, () -> b)` |
| `a ?: 0` (a number) | `Nulls.or(a, 0)`; after `?.`: `final var $t1 = Nulls.map(...); Nulls.or($t1, 0)` |
| `a!!` | `java.util.Objects.requireNonNull(a)` |
| `String.class`, `Int.class` | `String.class`, `Integer.class` |
| `a?.m(x)` | `Nulls.map(a, $n1 -> $n1.m(x))`; the lambda covers the rest of the chain |
| `f(a?.m())` | `final var $t1 = Nulls.map(a, $n1 -> $n1.m()); f($t1)`; where no statement can go, `f(Nulls.typed(Nulls.map(...)).value())` |
| `a?.m()` as a statement | `final var $n1 = a; if ($n1 != null) { $n1.m(); }` |
| `attempt X else e -> Y` | `Result.attempt(() -> X, e -> Y)`; without a name the parameter is `$ex1` |
| `val x = try r` | `final var $t1 = r; if ($t1.isErr()) return Result.propagate($t1); final var x = Result.unwrap($t1);` |
| `try r` as a statement | the same without the last declaration |
| `f(a(), try r, b())` | `final var $t1 = a(); final var $t2 = r; if ($t2.isErr()) return Result.propagate($t2); f($t1, Result.unwrap($t2), b())` |
| `c && try r` | `boolean $t1 = false; if (c) { ...; $t1 = Result.unwrap($t2); }` (`true` and `if (!(c))` for `\|\|`); `if (c && try r) { ... }` without `else` becomes `if (c) { ...; if (Result.unwrap($t2)) { ... } }` |
| `a.f += try r` | `final var $t1 = a.f; ...; a.f = $t1; a.f += Result.unwrap($t2);` |
| `val x: T = a ?: try r` | `@Nullable T $t1 = a; if (java.util.Objects.isNull($t1)) { ...; $t1 = Result.unwrap($t2); } final T x = $t1;` (`var $t1` without a written type) |
| `while (try r) { ... }` | `while (true) { ...; if (!(Result.unwrap($t1))) break; ... }` |
| `Ok(x)` / `Err(x)` | `new Ok<>(x)` / `new Err<>(x)` |
| `Foo(x)`, `Foo<T>(x)` | `new Foo(x)`, `new Foo<T>(x)` |
| `println(x)` | `System.out.println(x)` |
| `{ x -> e }`, `{ it > 0 }` | `x -> e`, `it -> it > 0` |
| `xs \|> filter { it > 0 } \|> map { it * 2 }` | `xs.stream().filter(it -> it > 0).map(Nulls.nonNull(it -> it * 2)).toList()`; `Nulls.nonNull` makes NullAway reject a `map` that may produce null |
| `xs \|> filter { it != null }` | `Nulls.nonNulls(xs.stream().filter(it -> it != null)).toList()`; `Nulls.nonNulls` tells NullAway that the elements are not null, so the result fits a `List<String>` |
| `xs \|> first` | `Nulls.first(xs.stream().iterator())`, which gives `null` for an empty pipe and for a null first element |
| `xs \|> mapResult { f(it) }` | `final java.util.List<T> $p1 = new java.util.ArrayList<>(); for (final var it : xs) { final var $t1 = f(it); if ($t1.isErr()) return Result.propagate($t1); $p1.add(Result.<T>unwrap($t1)); }` |
| A `var` captured by `?:`, `?.` or `attempt` | copied first: `final var $c1 = count;` |

Generated names start with `$`, which Axolotl names cannot contain: `$t` for temporaries, `$n` for
`?.` parameters, `$ex` for `attempt` exceptions, `$c` for copies, `$p` for `mapResult` lists.
Counters restart in every function.
