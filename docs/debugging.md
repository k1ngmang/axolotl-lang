# Debugging and stack traces

Generated classes keep javac's `LineNumberTable` and `SourceFile` (`Main.java`), so plain Java
tools keep working on the generated code. On top of that each class gets a JSR-45
`SourceDebugExtension` with the stratum `Axolotl`, built from the `.axlmap`:

```
$ javap -v build/classes/java/main/app/Main.class
...
SourceDebugExtension:
  SMAP
  Main.java
  Java
  *S Axolotl
  *F
  + 1 Main.axl
  app/Main.axl
  *L
  1#1:1
  7#1:12
  8#1:13
  *E
```

The default stratum stays `Java`, so a debugger without Axolotl support still shows the
generated `.java` file. Debuggers that ask for the `Axolotl` stratum (the IntelliJ plugin, or
`jdb`/JDI with `ReferenceType.locationsOfLine("Axolotl", "Main.axl", line)`) set breakpoints and
step on `.axl` lines.

Who adds the SMAP:

| Tool   | How |
|--------|-----|
| CLI    | `axl build` (disable with `--no-smap`). |
| Gradle | The last action of every `compileJava` task, when `axl { smap = true }` (default). |
| Maven  | Goals `axl:smap` (`process-classes`) and `axl:smap-test` (`process-test-classes`). |

## Stack traces

Stack traces come from `LineNumberTable`, so they name `.java` lines. Three ways to see `.axl` lines:

- `axl run` prints exceptions from `main` with `.axl` lines.
- `axl retrace` rewrites a saved trace, using `.axlmap` files or the SMAPs in classes:
  ```
  axl retrace --map-dir build/generated/axl/main < trace.txt
  axl retrace --classpath build/classes/java/main trace.txt
  ```
  `at app.Main.main(Main.java:13)` becomes `at app.Main.main(Main.axl:8)`; other frames stay as they are.
- Failing JUnit tests: `org.axl:axl-smap` contains a JUnit Jupiter extension that rewrites the
  failure before the build tool records it. It is loaded through ServiceLoader when
  `junit.jupiter.extensions.autodetection.enabled=true`. The Gradle plugin adds both the
  dependency and the property to test tasks; for Maven add `axl-smap` as a test dependency and
  set the property in Surefire's `configurationParameters`, as `samples/maven-app` does.
