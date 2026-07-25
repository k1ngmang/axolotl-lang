# Debugging and stack traces

After a build the class files themselves report `.axl` positions. A build step that runs after
`javac` (see below) rewrites every class compiled from a generated `.java` file:

- `SourceFile` becomes the `.axl` file name (`Main.axl`).
- Every `LineNumberTable` entry is mapped from a generated Java line to an `.axl` line through the
  `.axlmap`. A Java line without a mapping (the generated constructor, the closing brace, the
  expansion of a `try`) keeps the closest mapped line above it. Lines are never invented: code that
  has no mapped line above it gets no entry and reports no line. Neighbouring entries of the same
  `.axl` line are merged.
- A JSR-45 `SourceDebugExtension` with the stratum `Axolotl` is added. Because the class file
  already holds `.axl` lines, the stratum maps each `.axl` line to itself.

```
$ javap -v build/classes/java/main/app/Main.class
...
  SourceFile: "Main.axl"
SourceDebugExtension:
  SMAP
  Main.axl
  Java
  *S Axolotl
  *F
  + 1 Main.axl
  app/Main.axl
  *L
  1#1:1
  3#1:3
  5#1,4:5
  *E
```

The default stratum stays `Java`, which is the class file as it is: a debugger or profiler that
knows nothing of Axolotl shows `Main.axl` and its lines. Debuggers that ask for the `Axolotl`
stratum (the IntelliJ plugin, or `jdb`/JDI with
`ReferenceType.locationsOfLine("Axolotl", "Main.axl", line)`) set breakpoints and step on `.axl`
lines too; both strata give the same line. The step runs once per class: classes that already name
an `.axl` file are not rewritten again.

Who runs the step:

| Tool   | How |
|--------|-----|
| CLI    | `axl build` (disable with `--no-smap`). |
| Gradle | The last action of every `compileJava` task, when `axl { smap = true }` (default). |
| Maven  | Goals `axl:smap` (`process-classes`) and `axl:smap-test` (`process-test-classes`); `<smap>false</smap>` or `-Daxl.smap=false` turns them off. |

The switch is the same one that always controlled the SMAP: with it off (`--no-smap`,
`smap = false`, `smap=false` in Maven) the classes are exactly what `javac` wrote, with
`SourceFile` `Main.java`, the generated Java lines and no SMAP, and the Gradle plugin does not add the
JUnit extension described below. There is no separate option for the line numbers alone, because the SMAP is only
consistent with the lines that are in the class. `axl run` does not need the step: it retraces
exceptions of the program it starts itself.

## Stack traces

A plain JVM stack trace of a program built with `axl build`, Gradle or Maven names the `.axl` file
and line, with no tool involved:

```
Exception in thread "main" java.lang.NumberFormatException: For input string: "x"
	at java.base/java.lang.NumberFormatException.forInputString(NumberFormatException.java:67)
	at java.base/java.lang.Integer.parseInt(Integer.java:661)
	at app.Main.parse(Main.axl:3)
	at app.Main.main(Main.axl:7)
```

This holds for logs, `Throwable.printStackTrace()`, `StackWalker`, exception reports of test runners
and APM agents alike. The frames of lambdas keep the name javac gave them (`lambda$main$0`); the
file and line in them are the `.axl` ones.

What else is there:

- `axl run` prints exceptions from `main` with `.axl` lines. It builds into a work directory and
  retraces the exception with the `.axlmap` files, so it works with or without the rewrite.
- `axl retrace` rewrites a saved trace that still names generated `.java` files: traces recorded
  before the rewrite existed, and traces of classes built with the SMAP switched off. Frames that
  already name an `.axl` file are left as they are.
  ```
  axl retrace --map-dir build/generated/axl/main < trace.txt
  axl retrace --classpath build/classes/java/main trace.txt
  ```
  `at app.Main.main(Main.java:13)` becomes `at app.Main.main(Main.axl:8)`; other frames stay as they are.
  `--map-dir` reads the `.axlmap` files and is the right choice for old traces. `--classpath`
  reads the SMAP of classes built by older versions of the tools (Java lines plus an SMAP); a class
  with `.axl` lines has nothing to retrace, so `--classpath` never rewrites a `.java` frame of it.
- Failing JUnit tests: `org.axl:axl-smap` contains a JUnit Jupiter extension that rewrites the
  failure before the build tool records it. Classes built with the rewrite do not need it, because
  their traces are right already; it only matters for classes of older builds and does not touch a
  frame that names an `.axl` file. It is loaded through ServiceLoader when
  `junit.jupiter.extensions.autodetection.enabled=true`. The Gradle plugin adds both the
  dependency and the property to test tasks; for Maven add `axl-smap` as a test dependency and
  set the property in Surefire's `configurationParameters`, as `samples/maven-app` does.
