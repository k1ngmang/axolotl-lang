plugins {
    application
}

dependencies {
    implementation(project(":axl-compiler-javac"))
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
