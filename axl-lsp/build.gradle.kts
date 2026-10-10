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
}

val vscode = rootProject.layout.projectDirectory.dir("editors/vscode")

val vscodeServer = tasks.register<Sync>("vscodeServer") {
    description = "Copies the language server into the VS Code extension."
    from(tasks.installDist.map { it.destinationDir.resolve("lib") })
    into(vscode.dir("server/lib"))
}

// vsce asks before packaging an extension without a license file.
val vscodeLicense = tasks.register<Copy>("vscodeLicense") {
    from(rootProject.file("LICENSE"))
    into(vscode)
}

// Not part of `check`: needs Node.js and npm.
tasks.register<Exec>("vsix") {
    description = "Packages the VS Code extension as editors/vscode/axolotl-<version>.vsix."
    dependsOn(vscodeServer, vscodeLicense)
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
