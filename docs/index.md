# Axolotl

Axolotl is a small language for the JVM that translates to readable Java 21. It keeps Java's types,
libraries and tools and adds two things Java lacks: null safety checked at compile time and errors
as values with `Result`.

```axl
package app

import java.nio.file.Files
import java.nio.file.Path

fn readPort(file: Path) -> Result<Int, String> {
    val text = try attempt Files.readString(file) else e -> "cannot read " + file + ": " + e.getMessage()
    return attempt Integer.parseInt(text.trim()) else "not a number: " + text
}

fn main() {
    val port = readPort(Path.of("port.txt"))
    println(port)
    val name: String? = System.getProperty("user.name")
    println("Hello, " + (name?.trim() ?: "stranger"))
}
```

Every `.axl` file becomes one Java class that you can read, debug and call from Java. There is no
runtime magic: the translation of every construct is listed in [the expansion table](expansion.md),
and the only library is `axl-runtime`, five small classes.

## What you get

- `T?` and `T`, checked by NullAway in JSpecify mode; `?.`, `?:` and `!!`.
- `Result<T, E>`, `attempt` to turn exceptions into errors and `try` to pass errors up.
- Pipes over collections: `names |> filter { !it.isBlank() } |> map { it.trim() }`.
- Java interop in both directions in the same module, without wrappers.
- Errors, stack traces and breakpoints at `.axl` lines, in the build, in tests and in the IDE.
- A Gradle plugin, a Maven plugin, a command-line compiler, a language server with a VS Code
  extension and an IntelliJ IDEA plugin.

## Where to go next

- [Getting started](getting-started.md) builds a first project in ten minutes.
- [Installation](install.md) covers Gradle, Maven, the command line and the editors.
- [The language](language.md) describes every construct.
- [Migrating from Java](migration.md) and [leaving Axolotl](leaving.md) explain how to move code in
  and out.
- [FAQ](faq.md) answers common questions, most of them about NullAway.
- [Stability](stability.md) says what is stable before 1.0 and how changes are announced.
