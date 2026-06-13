# Language server

`axl-lsp` is a Language Server Protocol server over standard input and output, used by the
VS Code extension in `editors/vscode` and by the IntelliJ plugin (Ultimate) through the
platform's LSP support. Start it with the Error Prone JVM options (`axl-lsp` from
`./gradlew :axl-lsp:installDist` already has them).

| Feature | Details |
|---------|---------|
| Diagnostics while typing | Syntax and the translator's rules (`try` placement, shadowing, `$` in names, missing `package`, ...). |
| Diagnostics on save | The whole module is translated and compiled with javac, Error Prone and NullAway, and so are the modules that include the file, such as its tests; errors appear on the `.axl` lines, also of files that are not open, and errors in Java sources on their own lines. |
| Highlighting | TextMate grammar `editors/vscode/syntaxes/axl.tmLanguage.json` plus semantic tokens. |
| Completion | Keywords that fit the place, names visible at the cursor, `println`/`Ok`/`Err`, and after a dot the members of a type: static members of a class, instance members of a declared, inferred or narrowed type, of a constructor call and of `this`; in imports the packages, classes and static members. |
| Go to definition | Locals, parameters and functions in the file; functions of other `.axl` files through `import fn` or `Class.fn`; fields, methods and enum constants of Axolotl types and types nested in other files; Java classes and members in Java source roots. |
| Hover | Declared, inferred or narrowed type of a name in Axolotl's names, members of Axolotl types, and the Java generated from the line. |
| Command `axl.showGeneratedJava` | Returns the Java translation of a document. |

## Modules and classpath

The server looks for module descriptions in the workspace:

- `axl.json` in a module directory, written by hand;
- `build/axl/axl.json`, written by the Gradle task `axlLspConfig`;
- `target/axl/axl.json`, written by the Maven goal `axl:lsp-config`.

```json
{
  "sourceRoots": ["src/main/axl"],
  "javaSourceRoots": ["src/main/java"],
  "classpath": ["/home/me/.m2/repository/com/example/lib/1.0/lib-1.0.jar"],
  "strictNull": false
}
```

A description of several modules lists them under `"modules"`, each with the fields above. A
module lists its own source roots first, then the ones it only sees, so that the tests use the
main sources while the main sources do not see the tests or their libraries:

```json
{
  "modules": [
    {"sourceRoots": ["src/main/axl"], "javaSourceRoots": ["src/main/java"], "classpath": ["lib/a.jar"]},
    {"sourceRoots": ["src/test/axl", "src/main/axl"], "javaSourceRoots": ["src/test/java", "src/main/java"],
     "classpath": ["lib/a.jar", "lib/junit.jar"]}
  ]
}
```

`"javaSourcepath"` lists Java source roots that a module reads but does not compile, such as those
of the projects it depends on, whose class files are on its classpath: javac takes a class from
there when its source is newer than its class file, and errors in those sources are not shown.

Relative paths are relative to the module directory. A description that is not valid JSON of this
shape is skipped with a warning. The search skips `build`, `target` and `out` directories, apart
from the descriptions the plugins write there, and hidden directories such as `.git`. When a
description changes or a `src/NAME/axl` directory appears, the server finds the modules again, so
running `axlLspConfig` or `axl:lsp-config` needs no restart of the editor.

Without a description, every `src/NAME/axl` directory is a module with `src/NAME/java` as its
Java sources and only the Axolotl runtime on the classpath. Every source set other than `main`, such as `src/test/axl`, also sees `src/main/axl`
and `src/main/java`, as in Gradle and Maven. A file outside every module belongs to a module of
its own source root, found from its package. In every module, `.java` files next to `.axl` files in
the directory of their package are Java sources too, as `axl check` takes them.

A client that knows the modules can pass them as the initialization option `modules`, a list of
module entries as in a description, with absolute paths and the module directory as `"root"`.
The server then takes those modules and looks for no descriptions. The IntelliJ plugin does this
with the source roots and libraries of the IntelliJ modules.

### Multi-project builds

In a build of several projects, such as `samples/multi-module-gradle` where `app` depends on
`core`, the directory layout does not tell which project uses which, so without descriptions
`app` gets false errors for everything it takes from `core`. Run `axlLspConfig`: the description
of `app` then lists the source roots of `core` after its own and leaves `core`'s classes and jar
out of the classpath, so edits to `core` are seen in `app` before a build. The libraries stay those
`app` compiles against: one that `core` takes with `implementation` is not offered in `app`, since
`compileJava` would not find it there either. `axl:lsp-config` writes
the classpath as Maven resolves it, so a sibling Maven module is seen through its compiled classes
or its installed jar, which only a build updates.

## VS Code

`./gradlew :axl-lsp:vsix` (needs Node.js) copies the server into the extension and packages
`editors/vscode/axolotl-0.1.1.vsix`. Install it with `code --install-extension axolotl-0.1.1.vsix`.
