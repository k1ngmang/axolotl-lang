# Axolotl for Visual Studio Code

Language support for `.axl` files:

- syntax highlighting (TextMate grammar plus semantic tokens from the server);
- errors while typing (syntax, shadowing, `try` placement) and, on save, javac, Error Prone and
  NullAway errors of the whole module, on the `.axl` lines;
- completion of keywords, names in scope and members of Java types;
- go to definition in `.axl` files and into Java sources;
- hover with the declared or inferred type and the generated Java;
- **Axolotl: Show Generated Java** opens the Java translation of the current file.

The language server needs a JDK 21 or newer (`axl.java.home`, `JAVA_HOME` or `java` on the PATH).
The modules and their classpath come from `axl.json`, or from `build/axl/axl.json` / `target/axl/axl.json`
written by the Gradle task `axlLspConfig` and the Maven goal `axl:lsp-config`; see docs/lsp.md.

Build the extension with `./gradlew :axl-lsp:vsix`; the result is `editors/vscode/axolotl-0.1.0.vsix`.

License: the same as the Axolotl repository.
