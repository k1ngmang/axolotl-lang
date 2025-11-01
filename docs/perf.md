# Cost of `?:` and `?.`

`a ?: b` expands to `Nulls.or(a, () -> b)` and `a?.m()` to `Nulls.map(a, $n1 -> $n1.m())`.
The benchmark in `benchmarks/` compares them with the hand-written checks
`a != null ? a : b` and `a == null ? null : a.m()`.

Run it with `./gradlew :benchmarks:jmh`.

## Results

OpenJDK 21.0.12, Linux x86_64, 4 cores; 2 forks, 5 warmup and 5 measurement iterations of 1 s.

| Benchmark        | Input   | ns/op         |
|------------------|---------|---------------|
| `?:` via `Nulls` | present | 0.675 +/- 0.059 |
| `?:` manual      | present | 0.664 +/- 0.030 |
| `?:` via `Nulls` | absent  | 0.648 +/- 0.032 |
| `?:` manual      | absent  | 0.680 +/- 0.084 |
| `?.` via `Nulls` | present | 1.010 +/- 0.141 |
| `?.` manual      | present | 1.039 +/- 0.108 |
| `?.` via `Nulls` | absent  | 0.460 +/- 0.045 |
| `?.` manual      | absent  | 0.458 +/- 0.050 |

The differences are below 2% and inside the error margin: once C2 has compiled the call site
the helper and the lambda are inlined completely.

## `--inline-elvis`

The benchmark measures a hot, monomorphic call site. In a large program one `Nulls.or`
receives many different lambdas, and in cold or interpreted code nothing is inlined. For
those cases `--inline-elvis` (and `inlineElvis = true` in the Gradle and Maven plugins)
expands a `?:` at the root of a statement without a lambda:

```java
final var $t1 = a;
return $t1 != null ? $t1 : b;
```

Only the root of a `val`/`var` initializer, an assignment or a `return` value is expanded,
because hoisting `a` from deeper inside an expression could change the evaluation order.
