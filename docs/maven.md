# Maven plugin `axl-maven-plugin`

Requires Maven 3.9 and Java 21. A complete, working configuration is in
[`samples/maven-app`](https://github.com/succubus-uwu/axolotl/blob/main/samples/maven-app/pom.xml); a new project can be created with the archetype:

```
mvn archetype:generate -DarchetypeGroupId=org.axl -DarchetypeArtifactId=axl-archetype-quickstart \
    -DarchetypeVersion=0.2.0 -DgroupId=org.example -DartifactId=demo -Dpackage=org.example.demo
```

## Goals

| Goal                | Default phase           | What it does |
|---------------------|-------------------------|--------------|
| `axl:generate`      | `generate-sources`      | `src/main/axl` to `target/generated-sources/axl`, added as a compile source root. |
| `axl:generate-test` | `generate-test-sources` | `src/test/axl` to `target/generated-test-sources/axl`, added as a test source root. |
| `axl:smap`          | `process-classes`       | Rewrites `target/classes` to report `.axl` files and lines and adds a JSR-45 SMAP (see [debugging](debugging.md)). |
| `axl:smap-test`     | `process-test-classes`  | The same for `target/test-classes`. |
| `axl:check`         | none                    | Compiles the main sources again with Error Prone and NullAway and prints errors of generated files at their `.axl` lines. |
| `axl:lsp-config`    | none                    | Writes `target/axl/axl.json` for [the language server](lsp.md): a module of the main sources with the compile classpath, and one of the test sources, which also sees the main ones, with the test classpath. |

Generation is incremental: a state file in `target/axl`, named after the output and the source
directory (`generated-sources_axl-from-src_main_axl.state.json`,
`generated-test-sources_axl-from-src_test_axl.state.json`), stores a SHA-256 of every
`.axl` file and the files generated from it, and only changed files are translated again, together
with files whose top-level values have inferred types, which may depend on the changed or removed
ones. A change of the options, the plugin version, the classpath or the Java sources makes every file
translated again; for a classpath directory, such as `target/classes` of another module in the
reactor, every class file in it counts, while the module's own `target/classes` and
`target/test-classes` do not. Outputs of removed files are deleted, all of them when the source
directory itself is removed. Two files that declare the same class are reported as an error even
when only one of them changed. Several executions may write to the same output directory from
different source directories; the duplicate check then covers each source directory on its own.

The `.axlmap` line maps stay next to the generated `.java` files, where the javac plugin and
`axl:check` read them, so a sources jar made by `maven-source-plugin` contains them as well.

## Parameters

| Parameter     | Property          | Default | Meaning |
|---------------|-------------------|---------|---------|
| `javaRelease` | `axl.javaRelease` | `21`    | Sets `maven.compiler.release` unless the project already does. |
| `inlineElvis` | `axl.inlineElvis` | `false` | Expand statement-level `?:` without a lambda. |
| `smap`        | `axl.smap`        | `true`  | `axl:smap` and `axl:smap-test`: rewrite the classes to `.axl` lines and add the SMAP; `false` keeps the Java lines. |
| `strictNull`  | `axl.strictNull`  | `false` | Treat values from Java code without nullness annotations as nullable (AXL104). |
| `skip`        | `axl.skip`        | `false` | Skip the goal. |
| `nullAway`    | `axl.nullAway`    | `true`  | `axl:check` only: run Error Prone and NullAway. |

The directories are parameters too, without a property:

| Parameter              | Goals                             | Default                             |
|------------------------|-----------------------------------|-------------------------------------|
| `sourceDirectory`      | `generate`, `check`, `lsp-config` | `src/main/axl`                      |
| `outputDirectory`      | `generate`, `check`, `smap`       | `target/generated-sources/axl`      |
| `classesDirectory`     | `smap`                            | `target/classes`                    |
| `testSourceDirectory`  | `generate-test`, `lsp-config`     | `src/test/axl`                      |
| `testOutputDirectory`  | `generate-test`, `smap-test`      | `target/generated-test-sources/axl` |
| `testClassesDirectory` | `smap-test`                       | `target/test-classes`               |
| `outputFile`           | `lsp-config`                      | `target/axl/axl.json`               |

`smap` and `check` read the generated files and their `.axlmap` line maps from their own
`outputDirectory`, and `check` its own `sourceDirectory`. When `generate` uses other directories,
give the same ones to `smap` and `check`, for example in the plugin's `<configuration>` rather than
in one execution; otherwise `smap` finds no line maps and adds no SMAP. The same holds for
`generate-test` and `smap-test`.

## Error Prone, NullAway and `.axl` error positions

`maven-compiler-plugin` needs three things, all shown in the sample:

1. Error Prone, NullAway and `org.axl:axl-compiler-javac` in `annotationProcessorPaths`.
2. These `compilerArgs`:
   ```xml
   <arg>-XDcompilePolicy=simple</arg>
   <arg>--should-stop=ifError=FLOW</arg>
   <arg>-XDaddTypeAnnotationsToSymbol=true</arg>
   <arg>-Xplugin:ErrorProne -Xep:NullAway:ERROR -Xep:NarrowingCompoundAssignment:ERROR -Xep:IdentityBinaryExpression:WARN -Xep:AlwaysThrows:WARN -Xep:AlreadyChecked:OFF -Xep:BadImport:OFF -Xep:InlineMeSuggester:OFF -XepOpt:NullAway:JSpecifyMode=true -XepOpt:NullAway:OnlyNullMarked=true</arg>
   <arg>${axl.javacPlugin}</arg>
   ```
   `axl:generate` sets the property `axl.javacPlugin` to `-Xplugin:AxlDiagnostics <generated>=<sources>`,
   with both directories as file URIs in which `=` is written `%3D`.
   That javac plugin reports every error in generated code at the `.axl` file and line:
   ```
   [ERROR] /work/demo/src/main/axl/app/Main.axl:[5,5] value `trimmed` may be null: use `?.`, `?:` or check `if (trimmed != null)`
   ```
3. `.mvn/jvm.config` with the `--add-exports`/`--add-opens` options for `jdk.compiler`, because
   Error Prone and the javac plugin run inside Maven's JVM.

Without the javac plugin, errors point to the generated `.java` files; `mvn generate-sources axl:check`
then prints them at the `.axl` lines.

## Stack traces of failing tests

Failures of tests against classes processed by `axl:smap` already name `.axl` files and lines.
For classes of older builds, add `org.axl:axl-smap` as a test dependency and enable JUnit extension
autodetection in Surefire; failures are then reported with `.axl` lines:

```xml
<configuration>
  <properties>
    <configurationParameters>junit.jupiter.extensions.autodetection.enabled = true</configurationParameters>
  </properties>
</configuration>
```
