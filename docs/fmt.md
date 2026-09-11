# Formatter

`axl fmt` rewrites `.axl` files in place; directories are searched for `.axl` files. `axl fmt --check src` changes
nothing, lists the files that would change and exits with 1 if there are any. `axl fmt -` reads standard input and writes
the result to standard output. The language server (`textDocument/formatting`) and IntelliJ's *Reformat Code* use the
same formatter.

There is one style and no options:

- 4 spaces of indentation; braces as in the documentation (`fn f() {`, `} else {`, `{}` when empty, `{ x -> x + 1 }`);
- one space around binary operators, `=`, `->`, `?:` and `|>`, after commas and after the `:` of a type (`x: Int`), and on
  both sides of the `:` of a supertype or bound (`class A : B`, `<T : Comparable<T>>`);
- none before the `(` of a call or declaration, none inside `()` and `<>`, none after a unary `-`, `!` or before `!!` and `?`;
- at most one blank line in a row, one after the package and after the imports, and one between top-level declarations
  (declarations of one line each may stand together);
- imports as one group sorted by name, each with the comment lines above it;
- no trailing spaces and a final newline.

What the style does not decide stays as written: every line break (a newline ends a statement), the indentation of a line
that continues an expression relative to the line it continues (at least 4 spaces), comments with the spaces around
them, and the text of strings. Line ends (`\r\n`) and a byte order mark are kept.

A file with syntax errors is left unchanged and its errors are printed. Every formatted file is parsed and translated
again and must give the same syntax tree and the same Java; if not, it is left unchanged and `axl` reports an internal
error.
