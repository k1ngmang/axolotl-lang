# Maven plugin `axl-maven-plugin`

Requires Maven 3.9 and Java 21. A complete, working configuration is in
[`samples/maven-app`](https://github.com/succubus-uwu/axolotl/blob/main/samples/maven-app/pom.xml); a new project can be created with the archetype:

```
mvn archetype:generate -DarchetypeGroupId=org.axl -DarchetypeArtifactId=axl-archetype-quickstart \
    -DarchetypeVersion=0.1.0 -DgroupId=org.example -DartifactId=demo -Dpackage=org.example.demo
```

## Goals

| Goal                | Default phase           | What it does |
|---------------------|-------------------------|--------------|
| `axl:generate`      | `generate-sources`      | `src/main/axl` to `target/generated-sources/axl`, added as a compile source root. |
| `axl:generate-test` | `generate-test-sources` | `src/test/axl` to `target/generated-test-sources/axl`, added as a test source root. |
| `axl:smap`          | `process-classes`       | Adds a JSR-45 SMAP to `target/classes` (see [debugging](debugging.md)). |
| `axl:smap-test`     | `process-test-classes`  | The same for `target/test-classes`. |
| `axl:check`         | none                    | Compiles the main sources again with Error Prone and NullAway and prints errors of generated files at their `.axl` lines. |

Generation is incremental: `target/axl-state.json` (`axl-test-state.json` for tests) stores a
SHA-256 of every `.axl` file and the files generated from it, and only changed files are
translated again. Outputs of removed files are deleted.

## Parameters

| Parameter     | Property          | Default | Meaning |
|---------------|-------------------|---------|---------|
| `javaRelease` | `axl.javaRelease` | `21`    | Sets `maven.compiler.release` unless the project already does. |
| `inlineElvis` | `axl.inlineElvis` | `false` | Expand statement-level `?:` without a lambda. |
| `smap`        | `axl.smap`        | `true`  | `axl:smap` and `axl:smap-test`: add the SMAP. |
| `strictNull`  | `axl.strictNull`  | `false` | Treat values from Java code without nullness annotations as nullable (AXL104). |
| `skip`        | `axl.skip`        | `false` | Skip the goal. |
| `nullAway`    | `axl.nullAway`    | `true`  | `axl:check` only: run Error Prone and NullAway. |

## Error Prone, NullAway and `.axl` error positions

`maven-compiler-plugin` needs three things, all shown in the sample:

1. Error Prone, NullAway and `org.axl:axl-compiler-javac` in `annotationProcessorPaths`.
2. These `compilerArgs`:
   ```xml
   <arg>-XDcompilePolicy=simple</arg>
   <arg>--should-stop=ifError=FLOW</arg>
   <arg>-XDaddTypeAnnotationsToSymbol=true</arg>
   <arg>-Xplugin:ErrorProne -Xep:NullAway:ERROR -XepOpt:NullAway:JSpecifyMode=true -XepOpt:NullAway:OnlyNullMarked=true</arg>
   <arg>${axl.javacPlugin}</arg>
   ```
   `axl:generate` sets the property `axl.javacPlugin` to `-Xplugin:AxlDiagnostics <generated>=<sources>`.
   That javac plugin reports every error in generated code at the `.axl` file and line:
   ```
   [ERROR] /work/demo/src/main/axl/app/Main.axl:[5,5] value `trimmed` may be null: use `?.`, `?:` or check `if (trimmed != null)`
   ```
3. `.mvn/jvm.config` with the `--add-exports`/`--add-opens` options for `jdk.compiler`, because
   Error Prone and the javac plugin run inside Maven's JVM.

Without the javac plugin, errors point to the generated `.java` files; `mvn generate-sources axl:check`
then prints them at the `.axl` lines.

## Stack traces of failing tests

Add `org.axl:axl-smap` as a test dependency and enable JUnit extension autodetection in
Surefire; failures are then reported with `.axl` lines:

```xml
<configuration>
  <properties>
    <configurationParameters>junit.jupiter.extensions.autodetection.enabled = true</configurationParameters>
  </properties>
</configuration>
```
