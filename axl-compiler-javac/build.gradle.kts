plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":axl-compiler"))
    implementation(project(":axl-runtime"))
    testRuntimeOnly(libs.errorprone.core)
    testRuntimeOnly(libs.nullaway)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// The diagnostics plugin uses javac internals, which cannot be exported together with --release.
tasks.compileJava {
    options.release = null as Int?
    options.compilerArgs.addAll(listOf("api", "util").map { "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED" })
}

val conformanceDir = rootProject.layout.projectDirectory.dir("tests/conformance")

tasks.test {
    @Suppress("UNCHECKED_CAST")
    jvmArgs(rootProject.extra["errorProneJvmArgs"] as List<String>)
    inputs.dir(conformanceDir).withPropertyName("conformance").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("axl.conformance", conformanceDir.asFile.absolutePath)
    systemProperty("axl.updateGolden", providers.gradleProperty("updateGolden").getOrElse("false"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
