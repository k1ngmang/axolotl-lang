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
unless marked `private`. `fn main()` becomes `public static void main(String[] args)`, so its body
reads the command line arguments from `args`. A `main` with parameters is an ordinary function, not
an entry point.

Three functions need no import: `println(x)` and `print(x)` write to the standard output, with and
without a line end, and `readLine()` reads the next line of the standard input, or `null` at its end.
`Ok(v)`, `Err(e)` and the type `Result` need none either.

## Types

`Int`, `Long`, `Boolean`, `Double` are Java's primitives; inside generics and when nullable they are
the wrapper classes. Every other name is a Java type: `String`, `List<Int>`, `java.util.Map<String, Int>`.
`T?` allows `null`; `T` does not. Nullness is checked by NullAway in JSpecify mode. `Class<*>` and
`List<*>` are Java's `Class<?>` and `List<?>`: a type argument that is not known.
A character in single quotes, `'a'`, is a Java `Character`; text in double quotes is a `String`.

A data class, class, interface or function may take type parameters, written after its name:

```axl
data class Pair<A, B>(val first: A, val second: B)

interface Repository<K, V> {
    fn find(key: K) -> V?
}

fn largest<T : Comparable<T>>(xs: List<T>) -> T? { ... }
```

`T` on its own stands for any type, nullable ones included, so `Pair<String, Int?>` is allowed and a
`T` value may be null inside the declaration: it is used like a `String?`, with `?.`, `?:` or a null
check, and `x.toString()` on it is an error. `T : Object` takes every type without null, and
`T : Comparable<T>` the types with an order; a type argument outside the bound, `Sorted<Object>`, is an error
that names the bound and the types that fit it. An object of a generic class is created with its
arguments, `Pair<String, Int>("age", 41)`, or with `Pair<>(...)` where the type it is stored as tells
them. Calls of generic functions need no arguments: `largest(numbers)`. The functions of a file whose
type has parameters are static and cannot use them; they take their own.

## Statements

- `val x = e` declares a final local; `var x = e` a mutable one. A type is optional, except when the
  value is `null` or a lambda: `val x: String? = null`. A `var` without a type has the type of its
  first value, so `var s = "x"` cannot take null later; write `var s: String? = "x"` for that.
- `x = e`, `x += e`, `-=`, `*=`, `/=`, `%=` assign to a `var` (or to a Java field).
- `if (c) { ... } else if (d) { ... } else { ... }`, `while (c) { ... }`, `return e`.
- `use (val reader = Files.newBufferedReader(path)) { ... } else e -> println(e)` opens a resource for the
  block and closes it after, also when the block throws. The `else` handles what opening, the block or
  `close()` throws, a call or a `throw`; it is needed when `close()` declares a checked exception. Without it, the error names the resource whose `close()` throws: `close()` of `BufferedReader` can
  throw `IOException`.
- `for (x in xs) { ... }` goes over a `List`, a `Set`, any `Iterable` or an array. `x` is a `val`.
  `break` leaves the loop around it and `continue` goes on with its next round, also from inside a
  `when` in the loop; they have no labels.
  A `while` or `for` body always has braces, like an `if` statement.
- Statements end at the end of a line or at `;`. A line starting with `(`, `+` or `-` starts a new
  statement; break long lines after an operator or before `.`, `?.`, `?:` and `|>`. Two statements
  on one line need a `;` between them, and an expression that does nothing, such as `x + 1`,
  cannot stand as a statement.
- Local names cannot shadow other local names.

## Expressions

Axolotl has no bit operators (`&`, `|`, `^`, `~`, `<<`, `>>`); use a `Set` or `java.util.BitSet`, or write the
arithmetic in a small Java class of the project (`src/main/java`) and call it.

Calls, member access, `+ - * / %`, comparisons, `== !=` (Java's `==`: values for numbers, booleans and enum constants, an error for strings and other objects), `&& || !`, string
concatenation, lambdas, method references `String::length` and class literals `String.class` work
as in Java (`Int.class` is `Integer.class`, the class a Java API such as Jackson wants). A call of a
capitalized name creates an object: `StringBuilder("x")`, `ArrayList<String>()`, also when it is
qualified by a package: `java.sql.Timestamp(1L)`. A generic class needs its type arguments,
`ArrayList<String>()`, or `ArrayList<>(xs)` where the type it is stored as gives them; a bare
`ArrayList()` is an error, since any value could go in, and so is `val xs = ArrayList<>()`, where
no type gives them. Names use the letters A to Z and a to z,
digits and `_`.

Lambdas are written in braces: `{ x -> x + 1 }`, `{ a, b -> a.compareTo(b) }`. Without an arrow,
`it` is the parameter if the body uses it: `{ it > 0 }`; `{ -> run() }` takes no parameters. Inside
a lambda that has `it`, a nested lambda without an arrow reads that `it`; otherwise `it` belongs to
the innermost lambda that uses it. A lambda with several statements gives its value with `return`:
`{ s -> val t = s.trim(); return t.length() }`; only a lambda of one expression gives that expression's value.
A lambda parameter takes its type from the call. Where Java cannot tell it, as in
`Comparator.comparing({ p -> p.city() }).thenComparing(...)`, write the type: `{ p: Person -> p.city() }`, or use a
method reference, `Person::city`.
A lambda cannot use a `var` that changes, also not inside a `?:`, `?.` or `attempt` in it; copy the
`var` to a `val` first.

## Data classes and interfaces

```axl
sealed interface Shape {
    val name: String
}

data class Circle(val name: String, val radius: Double) : Shape
data class Square(val name: String, val side: Double, val label: String?) : Shape
```

A `data class` is an immutable Java record: its fields are read with `circle.radius()` and it has
`equals`, `hashCode` and `toString`. An `interface` lists the values its implementations provide;
`sealed` limits the implementations to the file, so that a `when` can cover them all. Type names start
with a capital letter, and `Circle("c", 2.0)` creates one. They are nested in the class of their file
(`Shapes.axl` makes `Shapes.Circle`), so other files import them as `import app.Shapes.Circle`.
Functions over a record are plain functions that take it: `fn area(c: Circle) -> Double = ...`;
a body with methods is for the interfaces the data class implements.

An interface may also require methods, as signatures without a body:

```axl
interface Notes {
    val name: String
    fn add(text: String) -> Int
    fn find(id: Int) -> String?
}
```

Each `class` that implements it writes the methods (`fn add(text: String) -> Int { ... }`), with the
same parameter types and a result type that fits: `String` where `String?` is required, never the
other way. A data class implements such methods in a body after its fields, as it does
`Comparable`: `data class Money(val cents: Long) : Comparable<Money> { fn compareTo(o: Money) -> Int =
Long.compare(cents, o.cents()) }`. The body holds methods only; behavior over data classes otherwise
stays in functions at the top level, with `when` over the sealed interface.

## Classes

A `class` has state and methods:

```axl
class Counter(val name: String, var step: Int) {
    var count: Int = 0
    val history: ArrayList<Int> = ArrayList<Int>()

    fn next() -> Int {
        count += step
        history.add(count)
        return count
    }
}
```

The parameters of the class and the properties in its body are fields: `val` ones cannot be assigned,
`var` ones can, from inside the class (`count += step`) and outside (`counter.step = 5`). A property
has a value, and a type when the value does not tell it: `val items = ArrayList<String>()` and `var
count = 0` need none, `val cache: Map<String, Int>? = null` does. It is set after the parameters, in order. Methods are the `fn`s in the body
and read the fields by name. `private` hides a property or a method. A class cannot extend another one
and is created like a data class: `Counter("c", 1)`; it may implement interfaces of its package and Java interfaces. For a generic Java interface of the JDK write `Int`,
`Long`, `Double` or `Boolean` as the type argument and in the methods, as for your own interfaces: `class Doubler :
Function<Int, Int> { fn apply(x: Int) -> Int = x * 2 }`. The same holds for a generic interface from a library on the
classpath or from your own Java files, since the compiler reads their signatures. A property
without a written type may be initialized by a method of its class that has a written result type (`val total = compute()`).
Every parameter of a class is a property and is written with `val` or `var`: `class Dice(seed: Long) { val random =
Random(seed) }` is rejected, write `class Dice(val seed: Long)`. Axolotl has no parameters that only the constructor sees,
which keeps one way to declare state.

A class may extend a Java class by calling its constructor after the colon: `class NotFound(val id:
String) : RuntimeException("not found: " + id)`, `class Worker : Thread("worker") { fn run() { ... } }`.
Its methods override the Java class's by name, and interfaces follow after a comma. A class written in
Axolotl cannot be extended: keep one in a property and give both an interface.
A plain constructor parameter cannot be forwarded to the superclass: `class E(val code: Int, msg: String) :
RuntimeException(msg)` is rejected, write `val msg: String`.

A file whose name is a type it declares, such as `Counter.axl` above, is that type. Its other
declarations are static members: `Counter.start("c")`.

## Annotations

A nested JUnit class (`@Nested`) cannot be written, because Axolotl has no nested classes; use separate test files,
or a Java test class.

```axl
import org.junit.jupiter.api.Test
import fn org.junit.jupiter.api.Assertions.assertEquals

class ParseTest {
    @Test
    fn parsesANumber() {
        assertEquals(Ok(42), Parse.number("42"))
    }
}
```

`@Name` or `@Name(args)` before a function, a value, a class, a data class, an interface, an enum, a
property, a parameter or a field goes into the generated Java as it is, so JUnit, Jackson, JPA and
Spring see what they expect. The arguments are constants, as in Java: literals, names such as
`Color.RED`, `String.class` (`Int.class` is `Integer.class`), `name = value`, and `{ a, b }` for an
array. `@Override` is allowed and otherwise added where a method overrides one of `Object`. Nullness
is not an annotation but part of the type, so `@Nullable` and `@NonNull` are rejected in favor of `T?`.

## Enumerations

`enum Color { RED, GREEN, BLUE }` is a Java enum, nested like a data class: `Color.RED`,
`Color.values()`. Its constants may carry fields, declared after the name and given by each constant:
`enum Planet(val mass: Double, val radius: Double) { MERCURY(3.303e23, 2.4397e6), EARTH(5.976e24, 6.37814e6) }`,
read as `Planet.EARTH.mass`. Behavior stays in functions that `when` over the constants.

## `if` as a value and `throw`

`if (c) a else b` gives `a` or `b`; both branches are expressions (not braces, which start a lambda),
and `try` and `when` that give values cannot be in them. After `if`, a value whose type was tested
is narrowed as in a statement.

```axl
fn sign(n: Int) -> String = if (n < 0) "negative" else if (n == 0) "zero" else "positive"
```

`throw e` stands as a statement, as the value of a branch of a `when`: `else -> throw IllegalStateException("x")`,
or as the fallback of `?:`: `val user = users.get(id) ?: throw NotFound(id)` gives a `User` that is not null.

## Type tests and `when`

`x is Circle` and `x !is Circle` test the type of a value. A `val` or parameter that has been tested
reads as the new type where the test holds: in the `if` branch, to the right of `&&` (or of `||` for
`!is`), and after an `if` that leaves:

```axl
fn describe(shape: Shape) -> String {
    if (shape !is Circle) {
        return "not round"
    }
    return "radius " + shape.radius()
}
```

A type test checks the class only: `is List<*>` is the same as `is List`; other type arguments are an error.

A `var` can be tested but is not narrowed, since it may change. Neither is the result of a call, such as
`first.value() is Punct`: copy it to a `val` first (`val value = first.value()`) and test that, or name the
subject of a `when`: `when (val value = first.value())`.

`when` chooses a branch by type or by constant:

```axl
fn area(shape: Shape) -> Double = when (shape) {
    is Circle -> 3.14 * shape.radius() * shape.radius()
    is Square -> shape.side() * shape.side()
}

fn size(n: Int) -> String = when (n) {
    0 -> "none"
    1, 2 -> "few"
    else -> "many"
}
```

It becomes a Java `switch`. The subject of `is` branches is narrowed in each branch (`when (val e = f())`
names a subject that is not already a name). A branch is an expression or, after `->`, a block whose last
expression is the value; in a `when` statement a branch may also be a single statement without braces: `break`,
`continue`, `return` or `return value`, or an assignment (`2 -> continue`, `else -> n += x`); a `when` that gives a
value cannot be left with any of them. Branches with `is` must cover a sealed interface or end with `else`; a
`when` over constants that gives a value needs an `else`, unless it names every constant of an enum. The
sealed types and enums may be declared in the build or come from the classpath.
Constants are literals, negative numbers or names of constants such as `Color.RED`; a `Long`, `Double` or
`Boolean` subject is compared branch by branch. A subject that may be null goes to `else`, or to a
`null ->` branch, which may also stand next to `is` branches. A `when` stands as a statement, after `return` or as
the body of a function, or gives a value (`val x = when ...`); only the last form cannot contain `try` or
`return`, and none of them can be the single expression of a lambda or of a top-level value. A `when`
that gives a value cannot be the value of a class property, an argument after `?.` or part of a top-level
value or the body of an `attempt`; compute it in a function instead. A lambda written as a branch of an `if` value needs
parentheses: `if (c) ({ -> 1 }) else other`.

## Null safety

Passing a value of an unbounded type `T` to a Java parameter needs `T : Object` on the declaration, or a null check.

```axl
fn greet(name: String?) -> String = "Hello, " + (name?.trim() ?: "stranger")
```

- `a?.m()` calls `m` only when `a` is not null; otherwise the whole rest of the chain is skipped and
  the value is `null`. As a statement, `listener?.onEvent(e)` works with void methods.
- `a ?: b` is `a` unless it is null, then `b`; `b` is evaluated only when needed.
- `a!!` is `a` when it is not null and throws `NullPointerException` otherwise, at the `!!`.
- After `if (x != null)`, an early `return` or inside `&&`/`||`, a local, a parameter or a `val`
  property (also `a.b` when each step is a `val` and the local `a` is not assigned again) is known to
  be non-null. A `var` property or the result of a call such as `m.get(k)` may change in between, so
  it is copied to a local `val` first.
- `?` in a type argument counts too: `get` on a `List<String?>` returns `String?`, the `it` of a pipe
  over it may be null, `sorted` needs elements that are not null, and `|> first` gives `null` for a
  null element as for an empty pipe. `add` on an `ArrayList<String>` does not accept null, and
  `addAll`, `Collections.addAll` and `ArrayList<String>(xs)` do not take elements that may be null. This
  follows nested type arguments (`Map<String, List<String?>>`), copies such as `ArrayList<>(xs)` and
  `collect(Collectors.toList())`, and fields of Java generic classes. `xs |> filter { it != null }`
  drops the `?`: its value fits a `List<String>`.
- `Map.get` may return null, as NullAway knows, and so may the JDK methods that return null when
  there is no value: `poll` and `peek` of a `Queue`, `pollFirst`, `peekFirst` and the like of a
  `Deque`, `put`, `remove`, `putIfAbsent` and `replace` of a `Map` (the previous value) and its
  `computeIfPresent` (for a missing key), the navigation methods of `NavigableMap` and `NavigableSet`
  (`ceiling`, `firstEntry`, ...), `AtomicReference.get`, `getAndSet`, `getAndUpdate`, `getAcquire`,
  `getPlain`, `compareAndExchange` and the like, and `ThreadLocal.get`. A top-level `val` inferred
  from one of them is nullable.
- JDK methods that throw on null do not take a value that may be null: `"abc".contains(x)`,
  `concat`, `startsWith`, `compareTo`, `StringBuilder(x)`, `TreeMap.get(x)`, `List.of("a").contains(x)`,
  `PriorityQueue.add(x)`, `Hashtable.put(k, x)`, `Pattern.compile(x)`, `Path.of(x)` and `String.format(x)`
  (see docs/decisions.md for the whole list).
- A method reference is checked like the lambda it stands for: `xs.forEach(Main::print)` on a
  `List<String?>` needs a `print` that accepts null, and so do `P::new` and `ys::add` on an
  `ArrayList<String>`.
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
parameters (also when an Axolotl function hands it on as a parameter of a JDK interface type), and
a method reference passed to such code must accept null: not `Main::print` with a parameter without
`?`, `String::length` or `ys::add` on an `ArrayList<String>`.

Trusted in strict mode: static final fields of the JDK such as `System.out`, constants, the Axolotl
runtime, code in a `@NullMarked` scope, the stream operations that pipes expand to, what a JDK class
gives out of a type argument written in Axolotl without `?`, such as `get` on an `ArrayList<String>`,
and the JDK methods whose specification never returns null although nothing in the code says so:

- `toString()`, and the methods of `String`, `StringBuilder`, `CharSequence`, `Math`, `Object`
  (`getClass`), `Enum` (`name`, `getDeclaringClass`), `Record`, `Runtime`, `Thread.currentThread`,
  `Throwable.getStackTrace` and the number classes (except `Integer.getInteger` and `Long.getLong`,
  which read system properties);
- the collection factories `List.of`, `Set.of`, `Map.of`, `Map.copyOf`, `Map.entry`, the views and
  copies `entrySet`, `keySet`, `values`, `subList`, `iterator`, `toArray`, `headMap`, `descendingSet`
  and the like, `Arrays`, `Collections`, `Optional`, `Objects.requireNonNull`, the combinators of
  `Comparator`, `Map.Entry` and `java.util.function`, and the results of `Collectors`;
- `UUID`, `Locale`, `Random`, `Scanner`, `StringJoiner`, `Base64`, `Executors`, `CompletableFuture`,
  `Pattern` and `Matcher` (except `group`, which is null for a group that did not match);
- `Files` (except `probeContentType` and `getAttribute`), `Path` (except `getFileName`, `getParent`
  and `getRoot`), `File` (except `getParent`, `getParentFile`, `list` and `listFiles`), `Paths`,
  `Charset`, `StandardCharsets`, the readers, writers and streams of `java.io` (except
  `BufferedReader.readLine`), `java.net.http` and the factories of `URI` and `URL`;
- `java.time`, `java.text` (except a `parse` at a `ParsePosition`) and `java.math`;
- the getters of `Class` and `java.lang.reflect` that always have a value, such as `getName`,
  `getSimpleName`, `getMethods` and `getDeclaredConstructors`; `getRecordComponents`,
  `getAnnotation`, `getSuperclass` and `getEnumConstants` are null for a class without them and
  stay untrusted.

Everything else from Java code without annotations may be null, in particular the methods that give
null for a missing value: `Map.get`, `Map.put`, `Queue.poll` and `peek`, `System.getProperty` and
`getenv`, `Class.getAnnotation`, `BufferedReader.readLine`, `Matcher.group`, `Path.getParent`.
The elements of a `List<String>` returned by unannotated Java code may be null, also after `!!`, and
so may what `get` of an unannotated generic Java class returns. Such a list cannot be stored where
Axolotl writes `List<String>` (a local, a parameter, a property, a top-level value, a result type,
what a lambda kept in such a value returns, `addAll` or `ArrayList<String>(...)`), also through `?:`,
sorted or copied with `List.copyOf` until its nulls are dropped with `|> filter { it != null }`. It
can be stored as `List<String?>`, in either mode.

## Errors as values

A helper that always throws, such as `Assertions.fail`, is not an exit: after it the compiler still wants a value or
a `return`. Write `throw AssertionError("...")` instead. The `else` of `use` is a call or a `throw`; to turn the
failure into a value, put the `use` in a private function whose `else` throws, and call it with `attempt f(x) else e -> ...`.

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
  is not available: `attempt X else Y`. `Y` is the error of the `Result` and cannot be `null` unless the error type is written with `?`:
  `val r: Result<String?, Exception?> = attempt ... else e -> null`.
- `==` on strings, data classes and other values that define their own equality is an error: it would
  compare the objects, not their values; write `a.equals(b)`. `==` on numbers, booleans and enum constants
  compares the values, also for numbers taken from a collection, such as `xs.get(0) == xs.get(1)` on a
  `List<Int>`; nullable ones (`Int?`, `Map.get`) are an error: write `java.util.Objects.equals(a, b)`.
- `attempt X else e -> throw Y` gives the value of `X` itself, not a `Result`: a failure of `X` is
  a bug here, so `Y`, a `RuntimeException`, is thrown instead. This is how a Java method that
  declares a checked exception is called where no `Result` is wanted, such as in a test:
  `val response = attempt client.send(request, handler) else e -> throw IllegalStateException(e)`.
- On a line of its own, `attempt` runs a call and handles its failure: `attempt conn.commit() else
  e -> throw IllegalStateException(e)` or `attempt stream.close() else e -> println(e.getMessage())`.
  There is no value, so the call may return nothing, which is how `commit()`, `close()` and
  `Thread.sleep(1L)` are called when they declare a checked exception. The handler is a call or a `throw`.
  Alone in a lambda, `{ -> attempt Thread.sleep(1L) else e -> println(e) }`, such an `attempt` is the
  lambda's statement too, except in a pipe stage that gives a value, such as `map`, where it is the value.
  The handler may also leave the loop or the function: `attempt send(i) else e -> continue`, `else e -> break`,
  `else e -> return -1`, `else return`. It is a `catch` in the generated `try`, so `break` and `continue` need a loop
  around them and `return` needs a value when the function has a result; `e` is available in the `return` value.
  These exits stand only on a line of its own, not in `val r = attempt ...`, and `break` and `continue` not inside a
  lambda or a `when` that gives a value.
- `try r` unwraps an `Ok`, or returns the `Err` from the enclosing function, which must return
  `Result`. The error type must be the function's error type or a subtype of it, such as a class that implements
  the sealed interface the function returns; convert any other error first with `mapErr`:
  `try parse(s).mapErr({ code -> "error " + code })`.
- After `if (r is Err) { ...; return }` the `r` is an `Ok` and `r.value()` reads its value; after `if (r is Ok) { return }`
  it is an `Err` and `r.error()` reads the error, as in the `else` branch of such an `if`. (`r` must be a `val` or a parameter.)
  The same holds for a sealed interface of the build with two implementations: after `if (o is None) { return }` an
  `Option<T>` is a `Some`, and `if (o is None) "none" else o.value()` reads the value in its `else`.
- A `Result` also has `isOk()`, `isErr()`, `map({ v -> ... })`, which changes the value of an `Ok`, and
  `getOrElse(fallback)`.
- `try` binds like a unary operator and works anywhere in an expression:
  `Ok(try a() + try b())`, `if (try ready()) { ... }`, `while (try more()) { ... }`.
  Operands to its left are evaluated first, in order. `&&`, `||` and `?:` evaluate a `try` on their
  right side only when they need it.
- `try` is not allowed inside a lambda or `attempt` (it would return from the lambda), after `?.`
  and in `return try e` (write `return e`).
- An `if` or `when` with an `Ok(...)` or `Err(...)` branch, also in an `else if` or a nested `if`,
  takes its type from where it goes, a result or a `val` with a type:
  `val r: Result<Int, String> = if (c) Ok(1) else Err("x")`. Java cannot find the type of
  `val r = if (c) Ok(1) else Err("x")`, which is therefore an error, also when another branch is a
  `Result` such as `attempt ...`. A `map` stage whose lambda builds them takes the element type from
  `val rs: List<Result<Int, String>> = xs |> map { ... }`.

## Arrays and Java buffers

Axolotl has lists, not arrays. Where a Java method takes an array of objects, pass a list: `CompletableFuture.allOf(futures)`,
`Map.ofEntries(entries)` and `Path.of("a", parts)` get an array of the list, which keeps its type arguments; a `List.of(...)`
written in the call works too, and `List.of(Dot(1), Box(2))` of classes of the build that share one interface is a list of
that interface. This applies when every overload of the method with that many arguments that can take a list there takes
an array of a class, and the code tells that the value is a collection (a written or inferred type, or a `val` set from
`List.of`, a new collection or a pipe). A method that also takes a collection there (`ProcessBuilder(list)`, `String.join(",", list)`), or a varargs parameter
of `Object` that the list is one value of (`String.format("%s", list)`), gets the list; to pass its elements, write
`MessageFormat.format("{0}-{1}", values.toArray())`. The compiler warns about a list given alone to such a parameter.
`List.of(Dot(1), Box(2))` kept in a `val` without a type has a type only Java knows; write it: `val shapes: List<Shape> = ...`. For input and output buffers,
`ByteArray(n)` and `CharArray(n)` create Java's `byte[]` and `char[]`, which are also the type names: `fn copy(input:
InputStream, buffer: ByteArray)`. Pass them to Java (`read`, `write`, `String(buf, 0, n, UTF_8)`, `ByteBuffer.wrap(buf)`), read
`.length`, or go over them with `for (b in buf)`. Their elements are read and written by Java code:
`CharBuffer.wrap(row).put(2, '#')` writes an element through to the array, `CharBuffer.wrap(row).get(2)` reads one
(`ByteBuffer` for a `ByteArray`).

## Pipes

`|>` feeds a collection through Java stream operations; it also takes a `Stream` whose type the code tells (`Stream.of`,
`xs.stream()`, `Files.lines(p)`, `val s: Stream<String> = ...`) as it is. A stream of numbers, such as `IntStream.range(0, 4)`
or `Random().ints(...)`, goes through `|>` after `.boxed()`. The source of a pipe is a collection or a stream, whatever
its type is written as:

```axl
val evens = numbers |> filter { it % 2 == 0 } |> map { it * 10 }
val firstThree = names |> sorted |> distinct |> limit(3)
val count = numbers |> filter { it > 0 } |> count
numbers |> forEach { println(it) }
```

| Stage | Meaning |
|-------|---------|
| `filter { }`, `map { }`, `peek { }` | Stream operations with one element `it`. |
| `sorted`, `sorted(byAge)`, `sorted { a, b -> ... }` | Natural order, a `Comparator`, or a comparison. |
| `distinct`, `limit(n)`, `skip(n)` | As in `Stream`. |
| `forEach { }`, `anyMatch { }`, `allMatch { }`, `noneMatch { }`, `count`, `first` | End the pipe; `first` is the first element or `null`, so `xs \|> first ?: fallback` gives the fallback for an empty pipe. |
| `mapResult { }` | Maps every element to a `Result`; the first `Err` is returned from the function. |

`filter { it is Circle }` keeps the circles and the elements after it are `Circle`s: `shapes |> filter { it is
Circle } |> map { it.radius() }` is a `List<Double>`, and `filter { it is Circle }` alone is a `List<Circle>`.
`Ok`, `Err` and a generic data class keep the type arguments of the elements: `results |> filter { it is Ok }
|> map { it.value() }` on a `List<Result<Int, String>>` is a `List<Int>`.
A pipe stored in a typed `val` or returned from a function may give a supertype of what `map` gives:
`val shapes: List<Shape> = xs |> map { Dot(it) }`, when only `distinct`, `limit` or `skip` follow the `map`.
Passed straight as an argument it does not; store it in a typed `val` first.
A `map` that gives null may be followed by `filter { it != null }`, which drops the nulls and gives a list
without them.

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

A library written in Axolotl gives Java the shape of Axolotl: final classes, lists instead of arrays, `Result`
instead of checked exceptions, `Character` for a character, and public or private members only. Where Java
callers need more, such as `String[]` results, varargs or a `throws` clause, add a Java interface to the module
whose default methods adapt the Axolotl class, and let the class implement it:

```java
public interface CommandLineApi {
    List<String> argList();

    default String[] getArgs() {
        return argList().toArray(String[]::new);
    }
}
```
