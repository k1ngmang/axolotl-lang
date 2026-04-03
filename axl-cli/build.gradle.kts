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
