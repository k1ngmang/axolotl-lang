// The Maven plugin and the archetype are Maven projects; this build runs them with the Maven wrapper.
plugins {
    base
}

val testRepo = rootProject.layout.buildDirectory.dir("test-repo").get().asFile.toURI().toString()
val mavenRepo = rootProject.layout.buildDirectory.dir("maven-repo").get().asFile.absolutePath
val mvnw = if (System.getProperty("os.name").startsWith("Windows")) "mvnw.cmd" else "./mvnw"

fun Exec.maven(dir: File, vararg goals: String) {
    workingDir(dir)
    commandLine(listOf(file(mvnw).absolutePath, "-B", "-ntp", "-Daxl.testRepo=$testRepo", "-Dmaven.repo.local=$mavenRepo") + goals)
    environment("AXL_TEST_REPO", testRepo)
}

val mavenPlugin = tasks.register<Exec>("mavenPlugin") {
    description = "Builds axl-maven-plugin and runs its integration tests on samples/maven-app."
    dependsOn(
        ":axl-runtime:publishAllPublicationsToTestRepoRepository",
        ":axl-compiler:publishAllPublicationsToTestRepoRepository",
        ":axl-compiler-javac:publishAllPublicationsToTestRepoRepository",
    )
    inputs.file("pom.xml")
    inputs.dir("src")
    inputs.dir(rootProject.layout.projectDirectory.dir("samples/maven-app"))
    outputs.file("target/axl-maven-plugin-${project.version}.jar")
    maven(projectDir, "install")
}

val archetype = tasks.register<Exec>("archetype") {
    description = "Builds axl-archetype-quickstart and builds a project generated from it."
    dependsOn(mavenPlugin)
    val dir = file("axl-archetype-quickstart")
    inputs.dir(dir.resolve("src"))
    inputs.file(dir.resolve("pom.xml"))
    outputs.file(dir.resolve("target/axl-archetype-quickstart-${project.version}.jar"))
    maven(dir, "install")
}

tasks.check {
    dependsOn(mavenPlugin, archetype)
}
