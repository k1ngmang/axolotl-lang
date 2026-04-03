allprojects {
    group = "org.axl"
    version = "0.1.0"
}

// Error Prone runs inside javac in the same JVM and needs access to the compiler internals.
extra.set("errorProneJvmArgs",
    listOf("api", "code", "comp", "file", "main", "model", "parser", "processing", "tree", "util").flatMap {
        listOf(
            "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED",
        )
    }
)

subprojects {
    // Functional tests of the build plugins resolve the artifacts from this repository.
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "testRepo"
                    url = uri(rootProject.layout.buildDirectory.dir("test-repo"))
                }
            }
        }
    }
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(21)
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release = 21
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
