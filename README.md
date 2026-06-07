# Axolotl

Axolotl is a small JVM language that translates to readable Java 21. It adds null safety checked at
compile time (`T?`, `?.`, `?:`, `!!`) and errors as values (`Result`, `attempt`, `try`), and
otherwise uses Java's types, libraries and tools.

```axl
package app

fn parse(s: String) -> Result<Int, String> =
    attempt Integer.parseInt(s) else e -> "not a number: " + s

fn doubled(s: String) -> Result<Int, String> {
    val n = try parse(s)
    return Ok(n * 2)
}

fn main() {
    val name: String? = System.getProperty("user.name")
    println("Hello, " + (name?.trim() ?: "stranger"))
    println(doubled("21"))
}
```

Every `.axl` file becomes one Java class; Java code in the same module calls it directly and the
other way round. Errors, stack traces and breakpoints point to `.axl` lines.

## Installing

Installers come with the next release, 0.1.1: a `setup.exe` for Windows, a `.pkg` for macOS, a
`.deb` or a `.tar.gz` for Linux. Each brings its own Java runtime, puts `axl` on the `PATH` and sets
up the libraries for Maven and Gradle. The current [release](https://github.com/succubus-uwu/axolotl/releases),
0.1.0, has the `axl` zip, which needs Java 21, and the VS Code and IntelliJ plugins; its Gradle and
Maven plugins are built from source. See [installation](docs/install.md).

## Documentation

The documentation lives in [`docs`](docs/index.md) and is built with MkDocs
(`pip install mkdocs && mkdocs serve`). Start with [getting started](docs/getting-started.md).

## Building

JDK 21 is required. `./gradlew check` builds and tests everything, including the Maven plugin
(through the Maven wrapper) and the Gradle plugin functional tests. `./gradlew publishToMavenLocal`
installs the artifacts into `~/.m2/repository`.

| Directory | Contents |
|-----------|----------|
| `axl-runtime` | `Result`, `Ok`, `Err`, `Nulls`: the only runtime dependency. |
| `axl-compiler` | Parser, checks and Java generator. |
| `axl-compiler-javac` | Compilation with javac, Error Prone and NullAway; the `AxlDiagnostics` javac plugin. |
| `axl-smap` | JSR-45 SMAPs for debuggers and stack trace retracing. |
| `axl-cli` | The `axl` command. |
| `axl-gradle-plugin` | Gradle plugin `org.axl.lang`. |
| `axl-maven-plugin` | Maven plugin and the `axl-archetype-quickstart` archetype. |
| `axl-lsp`, `editors/vscode` | Language server and VS Code extension. |
| `axl-intellij` | IntelliJ IDEA plugin. |
| `tests/conformance` | Translation cases with expected Java, errors and output. |
| `samples` | Gradle and Maven example projects. |
