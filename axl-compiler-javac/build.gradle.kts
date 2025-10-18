plugins {
    `java-library`
}

dependencies {
    api(project(":axl-compiler"))
    implementation(project(":axl-runtime"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

val conformanceDir = rootProject.layout.projectDirectory.dir("tests/conformance")

tasks.test {
    inputs.dir(conformanceDir).withPropertyName("conformance").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("axl.conformance", conformanceDir.asFile.absolutePath)
}

tasks.test {
    systemProperty("axl.updateGolden", providers.gradleProperty("updateGolden").getOrElse("false"))
}
