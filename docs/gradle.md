# Gradle plugin `org.axl.lang`

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()          // until the plugin is published
        gradlePluginPortal()
    }
}
// The plugin adds the runtime, the javac plugin, Error Prone and NullAway as dependencies.
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

// build.gradle.kts
plugins {
    java
    id("org.axl.lang") version "0.2.0"
}
```

Put `.axl` files in `src/main/axl` and `src/test/axl`, next to `src/main/java`.
Gradle 8.5 or newer and Java 21 are required; the configuration cache is supported.

## What the plugin does

- Adds an `axl` source directory set to every source set (`sourceSets.main.axl`).
- Registers `generateAxl`, `generateTestAxl`, ... which translate `.axl` files into
  `build/generated/axl/<sourceSet>` (`.java` plus `.axlmap`). The task is cacheable and
  incremental: changed files are translated again, together with files whose top-level values have
  inferred types; removed files lose their outputs. A change of the Java sources, the classpath or an
  option translates every file again.
- Adds the generated directory to the Java sources, so `compileJava` compiles `.axl` and
  `.java` code together and both see each other. The classpath and the Java sources are inputs
  of the task only for the types of top-level values that call into them.
- Adds `org.axl:axl-runtime` to the `implementation` configuration of every source set (`api` too
  with `java-library`). In a multi-project build every project depends on the same artifact.
- Source sets other than `main`, such as `test`, custom source sets and JVM test suites, get the same
  `axl` directory (`src/<name>/axl`), a `generate<Name>Axl` task, the runtime and, with `smap`,
  `org.axl:axl-smap` in `runtimeOnly`, which rewrites stack traces of tests against classes built
  by older versions.
- Applies `net.ltgt.errorprone` and turns on NullAway in JSpecify mode for every `JavaCompile`.
- Reports errors of `compileJava` in generated files at their `.axl` file and line.
- Rewrites compiled classes to report the `.axl` file and lines in every stack trace, and adds a
  JSR-45 SMAP for debuggers (see [debugging](debugging.md)).
- Registers `axlLspConfig`, which writes `build/axl/axl.json` for [the language server](lsp.md):
  a module for every source set, with its `.axl` and Java source directories, its compile
  classpath and `strictNull`. A source set that compiles against another, as `test` does against
  `main`, or against another project of the build, also gets that one's `.axl` directories, so the
  editor sees their edits before a build, and its Java directories as `javaSourcepath`, read where
  newer than its classes but compiled only by its own build. It is not part of the build; run it
  once and again when dependencies change.

## Configuration

```kotlin
axl {
    addRuntime = true    // add org.axl:axl-runtime to implementation of every source set
    nullAway = true      // Error Prone + NullAway on compileJava
    javaRelease = 21     // --release, unless the task sets its own
    inlineElvis = false  // expand statement-level ?: without a lambda
    smap = true          // classes report .axl files and lines (stack traces, debuggers); false keeps the Java lines
    strictNull = false   // treat values from unannotated Java code as nullable
}
```

## How errors reach the `.axl` file

The plugin adds `org.axl:axl-compiler-javac` to the annotation processor path and passes
`-Xplugin:AxlDiagnostics` to javac. That javac plugin intercepts every diagnostic in a
generated file, looks up the `.axlmap` next to it and reports the same message at the `.axl`
file and line instead, so Gradle, IDEs and CI logs all point to the `.axl` source:

```
/work/app/src/main/axl/app/Main.axl:7: error: value `missing` may be null: use `?.`, `?:` or check `if (missing != null)`
    println(missing.length())
    ^
```

Intercepting the task output was rejected: it does not work with the configuration cache
and does not reach IDEs, which read diagnostics from javac directly. The javac plugin uses
compiler internals, so `compileJava` runs in a forked JVM with the needed `--add-exports`;
Error Prone needs the same.
