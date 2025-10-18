plugins {
    `java-library`
}

dependencies {
    api(project(":axl-compiler"))
    implementation(project(":axl-runtime"))
    implementation(libs.errorprone.core)
    implementation(libs.nullaway)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

val conformanceDir = rootProject.layout.projectDirectory.dir("tests/conformance")

tasks.test {
    @Suppress("UNCHECKED_CAST")
    jvmArgs(rootProject.extra["errorProneJvmArgs"] as List<String>)
    inputs.dir(conformanceDir).withPropertyName("conformance").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("axl.conformance", conformanceDir.asFile.absolutePath)
    systemProperty("axl.updateGolden", providers.gradleProperty("updateGolden").getOrElse("false"))
}
