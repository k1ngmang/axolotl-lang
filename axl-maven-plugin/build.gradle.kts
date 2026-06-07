// The Maven plugin and the archetype are Maven projects; this build runs them with the Maven wrapper.
plugins {
    base
}

val testRepo = rootProject.layout.buildDirectory.dir("test-repo").get().asFile.toURI().toString()
val mavenRepo = rootProject.layout.buildDirectory.dir("maven-repo").get().asFile.absolutePath
val mvnw = if (System.getProperty("os.name").startsWith("Windows")) "mvnw.cmd" else "./mvnw"
val quiet = gradle.startParameter.logLevel == LogLevel.QUIET

// Gradle shows the output of Exec tasks even with -q; the tests of the plugin print to it too.
// Internal, so that a run with -q does not count as a change and rebuild the plugin.
class QuietMaven(@get:Internal val quiet: Boolean) : CommandLineArgumentProvider {
    override fun asArguments() = if (quiet) listOf("-q", "-Dmaven.test.redirectTestOutputToFile=true") else listOf()
}

fun Exec.maven(dir: File, vararg goals: String) {
    workingDir(dir)
    commandLine(listOf(file(mvnw).absolutePath, "-B", "-ntp", "-Daxl.testRepo=$testRepo", "-Dmaven.repo.local=$mavenRepo") + goals)
    argumentProviders.add(QuietMaven(quiet))
    environment("AXL_TEST_REPO", testRepo)
}

val mavenPlugin = tasks.register<Exec>("mavenPlugin") {
    description = "Builds axl-maven-plugin and runs its integration tests on samples/maven-app."
    dependsOn(
        ":axl-runtime:publishAllPublicationsToTestRepoRepository",
        ":axl-compiler:publishAllPublicationsToTestRepoRepository",
        ":axl-compiler-javac:publishAllPublicationsToTestRepoRepository",
        ":axl-smap:publishAllPublicationsToTestRepoRepository",
    )
    inputs.file("pom.xml")
    inputs.dir("src")
    inputs.dir(rootProject.layout.projectDirectory.dir("samples/maven-app"))
    val version = project.version
    inputs.files(rootProject.layout.buildDirectory.dir("test-repo").map { repo ->
        repo.asFileTree.matching {
            listOf("axl-runtime", "axl-compiler", "axl-compiler-javac", "axl-smap").forEach {
                include("org/axl/$it/$version/*.jar", "org/axl/$it/$version/*.pom")
            }
        }
    }).withPropertyName("axlArtifacts")
    outputs.file("target/axl-maven-plugin-${project.version}.jar")
    // Maven does not resolve an artifact again once it is in the repository; drop ours so the freshly published modules are used.
    val axlArtifacts = File(mavenRepo, "org/axl")
    doFirst { axlArtifacts.deleteRecursively() }
    maven(projectDir, "install")
}

val archetype = tasks.register<Exec>("archetype") {
    description = "Builds axl-archetype-quickstart and builds a project generated from it."
    dependsOn(mavenPlugin)
    val dir = file("axl-archetype-quickstart")
    inputs.dir(dir.resolve("src"))
    inputs.file(dir.resolve("pom.xml"))
    inputs.files(mavenPlugin).withPropertyName("plugin")
    outputs.file(dir.resolve("target/axl-archetype-quickstart-${project.version}.jar"))
    maven(dir, "install")
}

// Maven builds into target, which the clean task of the base plugin does not know.
tasks.clean {
    delete("target", "axl-archetype-quickstart/target")
}

tasks.check {
    dependsOn(mavenPlugin, archetype)
}
