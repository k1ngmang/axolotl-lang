plugins {
    `java-gradle-plugin`
    `maven-publish`
}

dependencies {
    implementation(project(":axl-compiler"))
    implementation(libs.errorprone.gradle)
}

gradlePlugin {
    plugins {
        create("axl") {
            id = "org.axl.lang"
            displayName = "Axolotl"
            description = "Compiles .axl sources together with Java, with Error Prone and NullAway."
            implementationClass = "org.axl.gradle.AxlPlugin"
        }
    }
}

tasks.processResources {
    val versions = mapOf(
        "axl" to project.version.toString(),
        "errorprone" to libs.versions.errorprone.asProvider().get(),
        "nullaway" to libs.versions.nullaway.get(),
    )
    inputs.properties(versions)
    filesMatching("**/versions.properties") {
        expand(versions)
    }
}

val functionalTest by sourceSets.creating

dependencies {
    "functionalTestImplementation"(platform(libs.junit.bom))
    "functionalTestImplementation"(libs.junit.jupiter)
    "functionalTestRuntimeOnly"(libs.junit.launcher)
    "functionalTestImplementation"(gradleTestKit())
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Builds the sample projects with the plugin."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    dependsOn(
        ":axl-runtime:publishAllPublicationsToTestRepoRepository",
        ":axl-compiler:publishAllPublicationsToTestRepoRepository",
        ":axl-compiler-javac:publishAllPublicationsToTestRepoRepository",
        "publishAllPublicationsToTestRepoRepository",
    )
    val samples = rootProject.layout.projectDirectory.dir("samples")
    inputs.dir(samples).withPropertyName("samples").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("axl.samples", samples.asFile.absolutePath)
    systemProperty("axl.testRepo", rootProject.layout.buildDirectory.dir("test-repo").get().asFile.absolutePath)
    providers.systemProperty("org.gradle.testkit.dir").orNull?.let { systemProperty("org.gradle.testkit.dir", it) }
}

tasks.check {
    dependsOn(functionalTestTask)
}
