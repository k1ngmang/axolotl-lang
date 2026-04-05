# Installation

## Installers

The release page has an installer for each system. Each one brings its own Java runtime, puts `axl`
on the `PATH` and runs `axl setup`, which copies the Axolotl libraries and the Maven and Gradle
plugins into `~/.m2/repository`. Maven and Gradle projects then build without anything else.

| System | File | How |
|--------|------|-----|
| Windows | `axolotl-<version>-windows-x64-setup.exe` | Run it. Open a new terminal afterwards so that it sees the new `PATH`. |
| macOS (Apple silicon) | `axolotl-<version>-macos-arm64.pkg` | Open it. The package is not signed: if macOS refuses, right-click it and choose *Open*. |
| Debian, Ubuntu | `axolotl-<version>-linux-amd64.deb` | `sudo apt install ./axolotl-<version>-linux-amd64.deb` |
| Other Linux | `axolotl-<version>-linux-x64.tar.gz` | Unpack it and run `install.sh`; it installs into `~/.local` without root. |

Then check it with `axl --version`. `axl setup` can be run again at any time, for example as another
user. Maven and Gradle themselves still need a JDK 21 of their own.

To remove it: Windows lists Axolotl under *Installed apps*; on Debian and Ubuntu run
`sudo apt remove axolotl`; on macOS delete `/Applications/axl.app` and `/usr/local/bin/axl`; after the
tarball delete `~/.local/share/axolotl` and `~/.local/bin/axl`.

## From source

With JDK 21, build Axolotl once and install it into the local Maven repository:

```
git clone https://github.com/succubus-uwu/axolotl.git
cd axolotl
./gradlew publishToMavenLocal
```

This installs the runtime, the compiler and both build plugins into `~/.m2/repository`.

## Gradle

Gradle 8.5 or newer. Add `mavenLocal()` to the plugin and dependency repositories and apply the
plugin:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

// build.gradle.kts
plugins {
    java
    id("org.axl.lang") version "0.1.0"
}
```

Sources go to `src/main/axl` and `src/test/axl`. See [the Gradle plugin](gradle.md) for the options.

## Maven

Maven 3.9 or newer. The installers already put the Maven plugin and the archetype into the local
repository. From source, install them after `publishToMavenLocal`; they are Maven projects:

```
cd axl-maven-plugin
./mvnw install -Dinvoker.skip=true
cd axl-archetype-quickstart
../mvnw install
```

The quickest start is then the archetype:

```
mvn archetype:generate -DarchetypeGroupId=org.axl -DarchetypeArtifactId=axl-archetype-quickstart \
    -DarchetypeVersion=0.1.0 -DgroupId=org.example -DartifactId=demo -Dpackage=org.example.demo
```

For an existing project, copy the configuration from
`samples/maven-app/pom.xml`; [the Maven plugin](maven.md) explains each part.

## Command line

The installers put `axl` on the `PATH`. From source:

```
./gradlew :axl-cli:installDist
export PATH="$PWD/axl-cli/build/install/axl/bin:$PATH"

axl run src/app/Main.axl                 # translate, compile and run one program
axl build src -d out/classes             # compile a source tree
axl check src/main/axl src/main/java     # print errors only
axl retrace trace.txt -cp out/classes    # rewrite a stack trace to .axl lines
```

`axl build` removes the `.java`, `.axlmap` and `.class` files it generated for `.axl` files that
were deleted since its previous run with the same source directory; it keeps a list of them in
`.axl-build` in the directory of the generated sources.

Common options: `--no-null-checks` skips Error Prone and NullAway, `--strict-null` turns on
[strict null mode](language.md#java-code-without-nullness-annotations), `--inline-elvis` expands a
statement-level `?:` without a lambda.

Options of `axl run` go before the `.axl` file; everything after it, options and `--` included, is
passed to the program unchanged: `axl run --no-null-checks app/Main.axl --verbose`.

## IntelliJ IDEA

IntelliJ IDEA 2024.2 or newer, Community or Ultimate:

```
./gradlew :axl-intellij:buildPlugin
```

Install `axl-intellij/build/distributions/axl-intellij-0.1.0.zip` with
*Settings | Plugins | Install Plugin from Disk*. Gradle and Maven projects are recognized on import.
See [the IntelliJ plugin](intellij.md).

## VS Code and other editors

```
./gradlew :axl-lsp:vsix
code --install-extension editors/vscode/axolotl-0.1.0.vsix
```

Building the extension needs Node.js. The extension starts the language server with the JDK from
the `axl.java.home` setting, `JAVA_HOME` or the `PATH`. Other editors can run the server from
`./gradlew :axl-lsp:installDist` over standard input and output; see [the language server](lsp.md).
