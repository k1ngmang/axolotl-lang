# Installation

## Installers

Since 0.2.0, the release page has an installer for each system. Each one brings its own Java
runtime, puts `axl` on the `PATH` and runs `axl setup`, which copies the Axolotl libraries and the Maven and Gradle
plugins into `~/.m2/repository`. Maven and Gradle projects then find Axolotl there; they still
download Error Prone, NullAway and their other dependencies from Maven Central.

| System | File | How |
|--------|------|-----|
| Windows | `axolotl-<version>-windows-x64-setup.exe` | Run it. It is not signed, so SmartScreen may stop it: choose *More info*, then *Run anyway*. Open a new terminal afterwards so that it sees the new `PATH`. |
| macOS (Apple silicon) | `axolotl-<version>-macos-arm64.pkg` | Open it. The package is not signed: if macOS refuses, open *System Settings > Privacy & Security* and choose *Open Anyway*, or run `xattr -d com.apple.quarantine axolotl-<version>-macos-arm64.pkg` first. |
| Debian 12+, Ubuntu 22.04+ | `axolotl-<version>-linux-amd64.deb` | `sudo apt install ./axolotl-<version>-linux-amd64.deb` |
| Other Linux | `axolotl-<version>-linux-x64.tar.gz` | Unpack it and run `install.sh`; it installs into `~/.local` without root, or into the directory `AXL_HOME` names. |

Step-by-step guides to the installation and the `axl` commands, as PDF in English and Russian, are in
[`docs/guides`](https://github.com/succubus-uwu/axolotl-lang/tree/main/docs/guides) and on the release page.

Then check it with `axl --version`. `axl setup` can be run again at any time, for example as another
user. It follows a `localRepository` in `~/.m2/settings.xml` and a `-Dmaven.repo.local` in
`MAVEN_OPTS`; `axl setup --repository <dir>` names the repository directly. The installers run
`axl setup` without your shell's environment, so run it again yourself if you set `MAVEN_OPTS`. Maven and Gradle
themselves still need a JDK 21 of their own. The bundled runtime has every module of Java SE, AWT and
Swing included, so the `.deb` depends on the X11, ALSA and font libraries.

To remove it: Windows lists Axolotl under *Installed apps*; on Debian and Ubuntu run
`sudo apt remove axolotl`; on macOS delete `/Applications/axl.app` and `/usr/local/bin/axl`, then
run `sudo pkgutil --forget org.axl.cli`; after the tarball delete `~/.local/share/axolotl` and
`~/.local/bin/axl`.

## From source

With JDK 21, build Axolotl once and install it into the local Maven repository:

```
git clone https://github.com/succubus-uwu/axolotl-lang.git
cd axolotl
./gradlew publishToMavenLocal
```

This installs the runtime, the compiler and the Gradle plugin into `~/.m2/repository`. The Maven
plugin and the archetype are Maven projects of their own; [Maven](#maven) shows how to install them.

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
    id("org.axl.lang") version "0.3.0"
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
    -DarchetypeVersion=0.3.0 -DgroupId=org.example -DartifactId=demo -Dpackage=org.example.demo
```

For an existing project, copy the configuration from
`samples/maven-app/pom.xml`; [the Maven plugin](maven.md) explains each part.

## Command line

The installers put `axl` on the `PATH`. From source:

```
./gradlew :axl-cli:installDist
export PATH="$PWD/axl-cli/build/install/axl/bin:$PATH"

axl run src/app/Main.axl                 # translate, compile and run one program
axl run -cp lib/sqlite-jdbc.jar src/app/Main.axl   # with a library
axl build src -d out/classes             # compile a source tree
axl check src/main/axl src/main/java     # print errors only
axl fmt src                              # format .axl files in place; --check lists them for CI, - reads stdin
axl retrace trace.txt -m out/classes     # rewrite an old stack trace (.java lines) to .axl lines
```

Libraries besides the Axolotl runtime go on `-cp` (or `--classpath`): jars and class directories
separated by `:` (`;` on Windows), repeatable. `axl run`, `axl check` and `axl build` take it alike;
`axl run` has the libraries on the classpath both when it compiles the program and when it runs it.
Paths in messages take the form of the arguments: absolute for an absolute source directory or file, else
relative to the working directory.

`axl build` removes the `.java`, `.axlmap` and `.class` files it generated for `.axl` files that
were deleted since its previous run with the same source directory; it keeps a list of them in
`.axl-build` in the directory of the generated sources. Its classes run with a `java` of your own,
which needs the Axolotl runtime and the libraries of `-cp` on its classpath:

```
RT=~/.m2/repository/org/axl/axl-runtime/0.3.0/axl-runtime-0.3.0.jar
java -cp out/classes:lib/sqlite-jdbc.jar:$RT app.Main
```

The runtime jar, `axl-runtime-<version>.jar`, is in the `lib` directory of the `axl` zip and of a
build from source, next to the other jars of an installed `axl` (`lib/app` on Linux, `app` on
Windows, `Contents/app` of `axl.app` on macOS) and, after `axl setup`, in the local Maven
repository as above.

Common options: `--no-null-checks` skips Error Prone and NullAway, `--strict-null` turns on
[strict null mode](language.md#java-code-without-nullness-annotations), `--inline-elvis` expands a
statement-level `?:` without a lambda. `--no-param-checks` leaves out the checks that public functions and constructors
make on entry against a `null` passed from Java for a non-null parameter. `--check-java` runs Error Prone and NullAway on the hand-written `.java` files of the
source directories too; by default they check only the code translated from `.axl` files, and javac alone compiles the rest.
`axl <command> --help` lists the options of each command.

Options of `axl run` go before the `.axl` file; everything after it, options and `--` included, is
passed to the program unchanged: `axl run --no-null-checks app/Main.axl --verbose`. The program
reads them from `args`, the `String[]` that every `fn main()` has; `fn main` itself takes no
parameters.

Exit codes: 0 on success; 1 when the sources have errors, or when the program that `axl run`
started throws; 2 for a wrong command line or a file that cannot be read. A program that calls
`System.exit` sets the code itself.

`axl run` compiles the source tree that the file's package starts at; `.axl` files below it whose package is not their
directory belong to another source tree and are left out (`--verbose` names them).

### Compiler daemon

Starting Java and loading javac, Error Prone and NullAway takes most of the time of a small build. The first
`axl check`, `axl build` or `axl run` therefore starts a daemon, a background Java process of your user that keeps
them loaded, and later commands hand their compilation to it: a small program then compiles and runs in about a
second. `axl run` still runs the program in its own process.

The daemon ends after 3 hours without work. `AXL_DAEMON_IDLE` sets another time, such as `30m` or `8h`. It also ends
when axl is upgraded or reinstalled. `axl daemon --status` lists the running daemons of every installation, each with its
installation directory, and marks the one of the `axl` you ran. `axl daemon --stop` stops the daemons of that installation
only; `axl daemon --stop --all` stops every one of your daemons, also those of other versions. The next command starts one
again.

`--no-daemon` (for example `axl run --no-daemon app/Main.axl`) or the environment variable `AXL_DAEMON=off` compile in
the command's own process. `axl` does the same by itself when the daemon cannot start or fails, and `--verbose` says
so. On Windows the daemon is used only when `axl` runs in a console, not when its output goes to a pipe.

Each version and installation of axl has its own daemon, under `~/.axl/daemon/<version>-<id>/`. That directory holds
the daemon's log, `daemon.log`, and the port and access token, readable by your user only. The daemon accepts
connections only from this machine, and only with that token.

## IntelliJ IDEA

IntelliJ IDEA 2024.2 or newer, Community or Ultimate. Install `axl-intellij-<version>.zip` from the
release page with *Settings | Plugins | Install Plugin from Disk*. To build it from source:

```
./gradlew :axl-intellij:buildPlugin
```

The plugin is then `axl-intellij/build/distributions/axl-intellij-0.3.0.zip`. Gradle and Maven
projects are recognized on import. See [the IntelliJ plugin](intellij.md).

## VS Code and other editors

The release page has the extension: `code --install-extension axolotl-<version>.vsix`. To build it
from source, which needs Node.js:

```
./gradlew :axl-lsp:vsix
code --install-extension editors/vscode/axolotl-0.3.0.vsix
```

The extension starts the language server with the JDK from the `axl.java.home` setting,
`JAVA_HOME` or the `PATH`. Other editors can run the server from
`./gradlew :axl-lsp:installDist` over standard input and output; see [the language server](lsp.md).
