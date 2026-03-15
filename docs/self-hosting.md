# The compiler written in Axolotl

Most of the compiler is written in Axolotl: the `.axl` files in `axl-compiler/src/main/axl` hold the
syntax tree, the checks, the code generator and the builder that turns the ANTLR tree into the syntax
tree. The rest stays in Java: the ANTLR parser and the code that drives it (`SourceParser`, which
subclasses ANTLR classes), the entry point `Translator` and the small records of the public API, which
many Java tools use. The Java that the compiler makes from those files
is kept in `axl-compiler/bootstrap` and compiled with the hand-written Java, so building the compiler
needs no compiler.

```
axl-compiler/
  src/main/antlr   the grammar
  src/main/java    hand-written Java
  src/main/axl     the same compiler's parts written in Axolotl
  bootstrap        the Java that src/main/axl translates to, checked in
```

Change an `.axl` file, then:

```
./gradlew :axl-cli:installDist      # builds a compiler from the current snapshot
./gradlew updateBootstrap           # translates src/main/axl with it and replaces the snapshot
./gradlew check                     # builds again from the new snapshot
```

`check` runs `verifyBootstrap`: the compiler built from the snapshot translates `src/main/axl` again
and the result must be the same files. When the snapshot is not a fixpoint, run `updateBootstrap`
once more until it is.

A change that makes the compiler reject or translate its own sources differently needs two steps:
first a version of the Java that still accepts the old sources, then the new sources.

A file whose name is the name of a type it declares (`Expr.axl` with `sealed interface Expr`) is that
type, so the Java code keeps using `org.axl.compiler.ast.Expr`, and its other declarations are members.
