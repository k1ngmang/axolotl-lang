plugins {
    application
}

dependencies {
    implementation(project(":axl-compiler-javac"))
    implementation(project(":axl-smap"))
    implementation(project(":axl-runtime"))
    implementation(libs.picocli)
    runtimeOnly(libs.errorprone.core)
    runtimeOnly(libs.nullaway)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    applicationName = "axl"
    mainClass = "org.axl.cli.Main"
    @Suppress("UNCHECKED_CAST")
    applicationDefaultJvmArgs = rootProject.extra["errorProneJvmArgs"] as List<String>
}

tasks.test {
    @Suppress("UNCHECKED_CAST")
    jvmArgs(rootProject.extra["errorProneJvmArgs"] as List<String>)
    systemProperty("axl.conformance", rootProject.layout.projectDirectory.dir("tests/conformance").asFile.absolutePath)
}

// The parts of the compiler written in Axolotl are kept as Java in axl-compiler/bootstrap, so that the
// compiler can be built without a compiler. This build of the compiler translates them again: the
// result must be the same files, which is what makes the snapshot a fixpoint.
val translateCompiler = tasks.register<JavaExec>("translateCompiler") {
    description = "Translates the Axolotl sources of the compiler with this build of the compiler."
    val sources = rootProject.layout.projectDirectory.dir("axl-compiler/src/main/axl")
    val output = layout.buildDirectory.dir("bootstrap")
    val compilerClasspath = sourceSets.main.get().runtimeClasspath
    inputs.dir(sources)
    inputs.files(compilerClasspath)
    outputs.dir(output)
    classpath = compilerClasspath
    mainClass = "org.axl.cli.Main"
    @Suppress("UNCHECKED_CAST")
    jvmArgs(rootProject.extra["errorProneJvmArgs"] as List<String>)
    argumentProviders.add(CommandLineArgumentProvider {
        val root = output.get().asFile
        root.deleteRecursively()
        listOf(
            "build", sources.asFile.path,
            "-s", root.resolve("java").path,
            "-d", root.resolve("classes").path,
            "--no-smap",
            "-cp", compilerClasspath.asPath,
        )
    })
}

// Compiles the translation with the hand-written Java of the compiler and nothing of the snapshot it
// replaces: translateCompiler links against the old classes, so a deleted or renamed .axl file would
// otherwise leave a snapshot that does not compile.
val compileTranslation = tasks.register<JavaCompile>("compileTranslation") {
    description = "Checks that the translated compiler compiles without the current snapshot."
    val compiler = project(":axl-compiler")
    source(translateCompiler.map { it.outputs.files.singleFile.resolve("java") })
    source(compiler.layout.projectDirectory.dir("src/main/java"))
    source(compiler.tasks.named("generateGrammarSource"))
    classpath = compiler.configurations["compileClasspath"]
    destinationDirectory = layout.buildDirectory.dir("bootstrap-classes")
}

tasks.register<Sync>("updateBootstrap") {
    description = "Replaces axl-compiler/bootstrap with the translation of the compiler's Axolotl sources."
    dependsOn(compileTranslation)
    from(translateCompiler.map { it.outputs.files.singleFile.resolve("java") }) {
        include("**/*.java")
    }
    into(rootProject.layout.projectDirectory.dir("axl-compiler/bootstrap"))
}

val verifyBootstrap = tasks.register("verifyBootstrap") {
    description = "Checks that axl-compiler/bootstrap is what this build of the compiler translates its sources to."
    dependsOn(translateCompiler, compileTranslation)
    mustRunAfter("updateBootstrap")
    val translated = layout.buildDirectory.dir("bootstrap/java")
    val snapshot = rootProject.layout.projectDirectory.dir("axl-compiler/bootstrap")
    doLast {
        val expected = translated.get().asFile.walkTopDown().filter { it.isFile && it.extension == "java" }
            .associate { it.relativeTo(translated.get().asFile).path to it.readText().replace("\r\n", "\n") }
        val actual = snapshot.asFile.walkTopDown().filter { it.isFile && it.extension == "java" }
            .associate { it.relativeTo(snapshot.asFile).path to it.readText().replace("\r\n", "\n") }
        val differing = (expected.keys + actual.keys).filter { expected[it] != actual[it] }.sorted()
        if (differing.isNotEmpty()) {
            throw GradleException("axl-compiler/bootstrap is out of date: ${differing.joinToString()}; run ./gradlew updateBootstrap")
        }
    }
}

tasks.check {
    dependsOn(verifyBootstrap)
}

// What `axl setup` copies into the local Maven repository: the libraries and both build plugins.
// Of the Maven wrapper's repository only the plugin and the archetype are taken, which Maven installed
// itself; the rest of it was resolved from the test repository and is marked as coming from there.
// Neither repository is ever cleaned, so only this version is taken. Metadata and checksums stay out:
// a local repository does not need them, and copied metadata would replace the user's own.
val bundledRepository = tasks.register<Sync>("bundledRepository") {
    description = "Collects the Axolotl artifacts that the installers carry."
    dependsOn(
        ":axl-runtime:publishAllPublicationsToTestRepoRepository",
        ":axl-compiler:publishAllPublicationsToTestRepoRepository",
        ":axl-compiler-javac:publishAllPublicationsToTestRepoRepository",
        ":axl-smap:publishAllPublicationsToTestRepoRepository",
        ":axl-gradle-plugin:publishAllPublicationsToTestRepoRepository",
        ":axl-maven-plugin:archetype",
    )
    val version = project.version.toString()
    from(rootProject.layout.buildDirectory.dir("test-repo")) {
        include("org/axl/*/$version/**", "org/axl/lang/org.axl.lang.gradle.plugin/$version/**")
    }
    from(rootProject.layout.buildDirectory.dir("maven-repo")) {
        include("org/axl/axl-maven-plugin/$version/**", "org/axl/axl-archetype-quickstart/$version/**")
    }
    exclude("**/maven-metadata*", "**/_remote.repositories", "**/*.md5", "**/*.sha1", "**/*.sha256", "**/*.sha512")
    into(layout.buildDirectory.dir("bundled/repository"))
}

// The released archives carry the repository next to lib, for `axl setup`. installDist leaves it out:
// from source, publishToMavenLocal installs the same artifacts.
listOf(tasks.distZip, tasks.distTar).forEach { archive ->
    archive.configure {
        into("axl-${project.version}/repository") {
            from(bundledRepository)
        }
    }
}

// Only jars: jpackage puts every jar under --input on axl's class path, which the programs that
// `axl run` starts inherit.
val jpackageInput = tasks.register<Sync>("jpackageInput") {
    description = "Collects the jars for jpackage."
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("jpackage/input"))
}

val windows = System.getProperty("os.name").startsWith("Windows")

// macOS requires the first number of a bundle version to be at least 1, so the major version is one
// higher there (see docs/decisions.md); `axl --version` and the installer's file name keep the real one.
fun packageVersion(version: String): String {
    if (!System.getProperty("os.name").startsWith("Mac")) {
        return version
    }
    val major = version.substringBefore('.')
    return "${major.toInt() + 1}." + version.substringAfter('.')
}
val jpackageTool = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    .map { it.metadata.installationPath.file(if (windows) "bin/jpackage.exe" else "bin/jpackage").asFile.path }

// javac runs inside axl, so the runtime needs jdk.compiler; the program that `axl run` starts uses the rest.
// These are the modules of java.se without java.desktop, whose native libraries need X11, ALSA and
// fonts, which a command-line compiler should not pull onto a server.
val runtimeModules = listOf(
    "java.compiler", "java.datatransfer", "java.instrument", "java.logging", "java.management",
    "java.management.rmi", "java.naming", "java.net.http", "java.prefs", "java.rmi", "java.scripting",
    "java.security.jgss", "java.security.sasl", "java.sql", "java.sql.rowset", "java.transaction.xa",
    "java.xml", "java.xml.crypto", "jdk.compiler", "jdk.unsupported", "jdk.zipfs", "jdk.crypto.ec", "jdk.charsets",
    "jdk.localedata", "jdk.management", "jdk.jdi", "jdk.jfr", "jdk.net", "jdk.httpserver",
)

val appImage = tasks.register<Exec>("appImage") {
    description = "Builds axl with its own Java runtime, the base of the native installers."
    dependsOn(jpackageInput)
    val input = layout.buildDirectory.dir("jpackage/input")
    val repository = bundledRepository.map { it.destinationDir }
    val dest = layout.buildDirectory.dir("jpackage/image")
    inputs.dir(input)
    inputs.files(bundledRepository)
    outputs.dir(dest)
    val version = packageVersion(project.version.toString())
    val jar = tasks.jar.flatMap { it.archiveFileName }
    @Suppress("UNCHECKED_CAST")
    val jvmArgs = rootProject.extra["errorProneJvmArgs"] as List<String>
    val modules = runtimeModules.joinToString(",")
    val onWindows = windows
    val onMac = System.getProperty("os.name").startsWith("Mac")
    doFirst { dest.get().asFile.deleteRecursively() }
    executable(jpackageTool.get())
    argumentProviders.add(CommandLineArgumentProvider {
        val args = mutableListOf(
            "--type", "app-image", "--name", "axl", "--app-version", version, "--vendor", "Axolotl",
            "--input", input.get().asFile.path, "--main-jar", jar.get(), "--main-class", "org.axl.cli.Main",
            "--add-modules", modules,
            // Lands in the parent directory of the jars on each system, where SetupCommand looks for it.
            "--app-content", repository.get().path,
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
            "--dest", dest.get().asFile.path,
        )
        jvmArgs.forEach { args += listOf("--java-options", it) }
        if (onWindows) {
            args += "--win-console"
        }
        if (onMac) {
            args += listOf("--mac-package-identifier", "org.axl.cli")
        }
        args
    })
}

// -PinstallerType=deb on Linux, pkg on macOS; Windows uses Inno Setup on the app image (installers/windows).
tasks.register<Exec>("nativeInstaller") {
    description = "Builds a native installer from the app image."
    dependsOn(appImage)
    val type = providers.gradleProperty("installerType")
    val image = layout.buildDirectory.dir("jpackage/image")
    val resources = rootProject.layout.projectDirectory.dir("installers")
    val dest = layout.buildDirectory.dir("installers")
    val version = packageVersion(project.version.toString())
    executable(jpackageTool.get())
    argumentProviders.add(CommandLineArgumentProvider {
        val appDir = image.get().asFile.listFiles()!!.single()
        listOf(
            "--type", type.get(), "--name", "axl", "--app-version", version,
            "--description", "The Axolotl compiler", "--app-image", appDir.path,
            "--resource-dir", resources.dir(type.get()).asFile.path,
            "--dest", dest.get().asFile.path,
        ) + when (type.get()) {
            // jpackage writes the Maintainer of a deb as "<vendor> <<maintainer>>".
            "deb" -> listOf(
                "--vendor", "succubus-uwu", "--linux-deb-maintainer", "keshindiy@gmail.com",
                "--linux-package-name", "axolotl", "--linux-app-category", "devel",
            )
            else -> listOf("--vendor", "Axolotl")
        }
    })
}

// For Linux distributions without dpkg: the app image with a script that installs it for the user.
// cp -a and the system tar keep the executable bits that Gradle's copies and archives drop.
tasks.register<Exec>("linuxTarball") {
    description = "Packs the app image with installers/linux/install.sh."
    dependsOn(appImage)
    val name = "axolotl-${project.version}"
    val image = layout.buildDirectory.dir("jpackage/image/axl").get().asFile.path
    val script = rootProject.layout.projectDirectory.file("installers/linux/install.sh").asFile.path
    val archive = layout.buildDirectory.file("installers/$name-linux-x64.tar.gz")
    inputs.dir(image)
    inputs.file(script)
    outputs.file(archive)
    workingDir(layout.buildDirectory)
    commandLine("sh", "-c", "rm -rf tarball && mkdir -p tarball/$name installers && cp -a '$image' tarball/$name/axl " +
        "&& cp '$script' tarball/$name/install.sh && chmod +x tarball/$name/install.sh " +
        "&& tar czf '${archive.get().asFile.path}' -C tarball $name")
}
