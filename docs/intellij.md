# IntelliJ IDEA plugin

The plugin supports IntelliJ IDEA Community and Ultimate 2024.2 and newer.

Build it with `./gradlew :axl-intellij:buildPlugin` and install
`axl-intellij/build/distributions/axl-intellij-<version>.zip` with
*Settings | Plugins | Install Plugin from Disk*.

## Features

| Area | What works |
|------|------------|
| Editing | File type and icon, highlighting with a color settings page, brace matching, `//` and `/* */` commenting, folding of blocks, lambdas, comments and imports, structure view. |
| Errors | Syntax errors from the PSI parser and the translator's rules (shadowing, `try` placement, `$` in names, ...) while typing. javac, Error Prone and NullAway errors of the module after a save: in Community through `axl check` from the bundled tools, in Ultimate through the language server. |
| Quick fixes | `return try e` to `return e`; `try` inside an expression to a separate `val`; `import a.B.f` to `import fn a.B.f`. |
| Navigation | Completion of names, keywords and members of Java and Axolotl types, also where an `is` test narrows a value; go to declaration in `.axl` files and into Java; find usages and rename of functions, values, types, properties, methods, data class fields and enum constants, including Java callers and `import fn` lines. |
| Java interop | Java code sees every `.axl` file as a class with its nested types, constructors and members (completion, navigation, error highlighting) without a build: the file is translated in memory and exposed through `PsiElementFinder` and `PsiShortNamesCache`. The Java the build generates (`build/generated/axl`, `target/generated-sources/axl`) is excluded from the index. |
| Run | A run icon next to `fn main()` creates a Java application run configuration for the file's class; with Gradle or Maven delegation the build tool compiles the `.axl` sources first. |
| Debug | Line breakpoints in `.axl` files, stepping by `.axl` lines and `.axl` positions in frames, through the JSR-45 stratum `Axolotl`. The translator's locals (`$t1`, `$n1`, ...) are labeled "Axolotl internal" in the Variables view. |
| Tools | *Show Generated Java* (editor popup and Tools menu) opens the translation read-only in a split. The intention *Convert Java file to Axolotl* converts a class with static members and nested records, enums, interfaces and classes, or a single record, enum, interface or class, when each part has a one to one Axolotl form. |
| Import | Gradle projects with `org.axl.lang` get `src/*/axl` as source roots from the plugin's source sets; Maven projects with `axl-maven-plugin` get `src/main/axl` and `src/test/axl`. |

## How it is built

- Lexer: the compiler's ANTLR lexer behind an IntelliJ `Lexer`.
- Parser: a recursive descent parser on `PsiBuilder` for the same grammar, with error recovery.
- References resolve with the translator's scope rules; Java classes come from `JavaPsiFacade`.
- The command-line compiler and the language server are bundled in `server/lib` and run in their own
  JVM with the options Error Prone needs.

Debugging is checked by hand, see [the debugger check](intellij-debug-check.md).
