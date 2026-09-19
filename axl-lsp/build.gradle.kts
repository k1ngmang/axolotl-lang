plugins {
    application
}

dependencies {
    implementation(project(":axl-compiler-javac"))
    implementation(project(":axl-runtime"))
    implementation(libs.lsp4j)
    implementation(libs.gson)
    runtimeOnly(libs.errorprone.core)
    runtimeOnly(libs.nullaway)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    applicationName = "axl-lsp"
    mainClass = "org.axl.lsp.Main"
    @Suppress("UNCHECKED_CAST")
    applicationDefaultJvmArgs = rootProject.extra["errorProneJvmArgs"] as List<String>
}

tasks.test {
    @Suppress("UNCHECKED_CAST")
    jvmArgs(rootProject.extra["errorProneJvmArgs"] as List<String>)
    // The tests look for the server's temporary directories; other builds on the machine make their own.
    val tmp = layout.buildDirectory.dir("tmp/test-files")
    doFirst { tmp.get().asFile.mkdirs() }
    systemProperty("java.io.tmpdir", tmp.get().asFile.absolutePath)
}

val vscode = rootProject.layout.projectDirectory.dir("editors/vscode")

val vscodeServer = tasks.register<Sync>("vscodeServer") {
    description = "Copies the language server into the VS Code extension."
    from(tasks.installDist.map { it.destinationDir.resolve("lib") })
    into(vscode.dir("server/lib"))
}

// Not part of `check`: needs Node.js and npm.
tasks.register<Exec>("vsix") {
    description = "Packages the VS Code extension as editors/vscode/axolotl-<version>.vsix."
    dependsOn(vscodeServer)
    workingDir(vscode)
    val npm = "npm install --no-audit --no-fund && npm run package"
    if (System.getProperty("os.name").startsWith("Windows")) {
        commandLine("cmd", "/c", npm)
    } else {
        commandLine("sh", "-c", npm)
    }
    inputs.files(vscode.asFileTree.matching { exclude("node_modules/**", "*.vsix") })
    outputs.file(vscode.file("axolotl-${project.version}.vsix"))
}
