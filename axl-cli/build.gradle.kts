plugins {
    application
}

dependencies {
    implementation(project(":axl-compiler-javac"))
    implementation(project(":axl-runtime"))
    implementation(libs.picocli)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    applicationName = "axl"
    mainClass = "org.axl.cli.Main"
}

tasks.test {
    systemProperty("axl.conformance", rootProject.layout.projectDirectory.dir("tests/conformance").asFile.absolutePath)
}
