plugins {
    java
}

val jmh by sourceSets.creating

dependencies {
    "jmhImplementation"(project(":axl-runtime"))
    "jmhImplementation"(libs.jmh.core)
    "jmhAnnotationProcessor"(libs.jmh.generator)
}

// Not part of `check`: run with `./gradlew :benchmarks:jmh`.
tasks.register<JavaExec>("jmh") {
    description = "Runs the JMH benchmarks."
    classpath = jmh.runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    args("-rf", "text", "-rff", layout.buildDirectory.file("jmh-result.txt").get().asFile.path)
}
