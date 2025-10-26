import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Ultimate only adds the LSP API, which the plugin uses when it runs in Ultimate.
        intellijIdeaUltimate("2024.2.6")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
        pluginVerifier()
        zipSigner()
    }
    implementation(project(":axl-compiler"))
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.13.4")
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
        changeNotes = """
            <ul>
              <li>First release: editing, Java interop without a build, running and debugging .axl files.</li>
            </ul>
        """.trimIndent()
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.6")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2024.2.6")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6")
        }
    }
}

// The compiler with Error Prone and the language server run in their own JVM from server/lib.
val server by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    server(project(":axl-cli"))
    server(project(":axl-lsp"))
}

tasks.prepareSandbox {
    from(server) {
        into("${project.name}/server/lib")
    }
}
