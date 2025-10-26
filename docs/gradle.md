# Gradle plugin `org.axl.lang`

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()          // until the plugin is published
        gradlePluginPortal()
    }
}

// build.gradle.kts
plugins {
    java
    id("org.axl.lang") version "0.1.0-SNAPSHOT"
}
```

Put `.axl` files in `src/main/axl` and `src/test/axl`, next to `src/main/java`.
Gradle 8.5 or newer and Java 21 are required; the configuration cache is supported.

## What the plugin does

- Adds an `axl` source directory set to every source set (`sourceSets.main.axl`).
- Registers `generateAxl`, `generateTestAxl`, ... which translate `.axl` files into
  `build/generated/axl/<sourceSet>` (`.java` plus `.axlmap`). The task is cacheable and
  incremental: only changed files are translated again, removed files lose their outputs.
- Adds the generated directory to the Java sources, so `compileJava` compiles `.axl` and
  `.java` code together and both see each other. The translator needs no classpath.
- Adds `org.axl:axl-runtime` to `implementation`. In a multi-project build every project
  depends on the same artifact.
- Applies `net.ltgt.errorprone` and turns on NullAway in JSpecify mode for every `JavaCompile`.
- Reports errors of `compileJava` in generated files at their `.axl` file and line.
- Adds a JSR-45 SMAP to compiled classes and rewrites stack traces of failing tests to
  `.axl` lines (see `docs/debugging.md`).

## Configuration

```kotlin
axl {
    addRuntime = true    // add org.axl:axl-runtime to implementation
    nullAway = true      // Error Prone + NullAway on compileJava
    javaRelease = 21     // --release, unless the task sets its own
    inlineElvis = false  // expand statement-level ?: without a lambda
    smap = true          // add a JSR-45 SMAP to classes (debuggers show .axl lines)
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
