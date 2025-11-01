# Error codes

Every diagnostic is printed as `file.axl:line:column: error: message`.

| Code   | Meaning |
|--------|---------|
| AXL001 | Syntax error. |
| AXL002 | The file has no `package` declaration. |
| AXL003 | `$` in a name; it is reserved for generated names. |
| AXL004 | A local variable or parameter shadows another one. |
| AXL005 | `return try e`; write `return e`. |
| AXL006 | `try` or `mapResult` inside a lambda or `attempt`, or after `?.`. |
| AXL007 | `try` or `mapResult` outside a function that returns `Result`. |
| AXL008 | Assignment to a `val` or a parameter. |
| AXL009 | A missing type: top-level `val`, a local initialized with `null` or a lambda, or the element type of `mapResult`. |
| AXL010 | A name declared twice in the same scope. |
| AXL011 | A Java keyword used as a name. |
| AXL012 | The file name is not a valid class name. |
| AXL013 | An unknown pipe stage, wrong arguments of a stage, or an ending stage that is not last. |
| AXL100 | Any other javac error, moved to the `.axl` line. |
| AXL101 | `try` on a `Result` whose error type differs from the function's. |
| AXL102 | Dereference of a value that may be null (NullAway). |
| AXL103 | A member imported without `fn`/`val`. |
