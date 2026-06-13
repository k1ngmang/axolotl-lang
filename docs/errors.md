# Error codes

Every diagnostic is printed as `file.axl:line:column: error: message`. The `axl` command adds the code at
the end, as in `[AXL100]`, so that it can be looked up here.

| Code   | Meaning |
|--------|---------|
| AXL001 | Syntax error, often a habit from Java or Kotlin that the message explains. Also a warning about a Kotlin string template such as `"Hello, $name"`, which keeps the `$` as text. |
| AXL002 | The file has no `package` declaration. |
| AXL003 | `$` in a name; it is reserved for generated names. |
| AXL004 | A local variable or parameter shadows another one. |
| AXL005 | `return try e`; write `return e`. |
| AXL006 | `try` or `mapResult` inside a lambda or `attempt`, or after `?.`. |
| AXL007 | `try` or `mapResult` outside a function that returns `Result`. |
| AXL008 | Assignment to a `val` or a parameter. |
| AXL009 | A missing type: a top-level `val` whose type cannot be inferred, a local initialized with `null` or a lambda, or the element type of `mapResult`. |
| AXL010 | A name declared twice in the same scope. |
| AXL011 | A Java keyword used as a name. |
| AXL012 | The file name is not a valid class name. |
| AXL013 | An unknown pipe stage, wrong arguments of a stage, or an ending stage that is not last. |
| AXL014 | Warning: the package does not match the file's directory. |
| AXL015 | Two files declare the same class. |
| AXL016 | A top-level value or a property of a class reads one declared after it, directly or through a function, lambda or value of the file or a function of another file, or values of two files read each other. |
| AXL017 | A data class or class does not give a value that an interface of the file requires, or gives it with a type that does not fit, such as `String?` for `String`. |
| AXL018 | Warning: code that has no effect, such as `!!`, `?.`, `?:` or a comparison with `null` on a name whose written type is not nullable, `null!!`, or a `var` that is never assigned again. |
| AXL100 | Any other javac or Error Prone finding, moved to the `.axl` line. Unknown names, type mismatches, wrong arguments and missing returns are reworded in Axolotl terms (`Int`, not `int` or `java.lang.Integer`), and Error Prone findings are shown without their `[Tag]`. An error that only follows from an earlier one, such as the uses of a name whose import failed, is left out. |
| AXL101 | `try` on a `Result` whose error type differs from the function's. |
| AXL102 | A value that may be null is dereferenced, unboxed, stored, returned (also from a lambda) or passed where null is not accepted. |
| AXL103 | A member imported without `fn`/`val`. |
| AXL104 | Strict null mode: a value from Java code without nullness annotations is used without a check. |
| AXL105 | `try` on a value that is not a `Result`. |
