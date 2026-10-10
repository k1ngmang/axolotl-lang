allprojects {
    group = "org.axl"
    version = "0.2.0"
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
            publications.withType<MavenPublication>().configureEach {
                pom {
                    licenses {
                        license {
                            name = "The Apache License, Version 2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        }
                    }
                }
            }
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
            // Temporary files of the tests stay in the build directory, where clean removes what a test leaves,
            // and builds of other checkouts on the machine do not see them.
            val tmp = layout.buildDirectory.dir("tmp/$name-files")
            doFirst { tmp.get().asFile.mkdirs() }
            systemProperty("java.io.tmpdir", tmp.get().asFile.absolutePath)
        }
        // Read back by `axl --version` and the language server.
        val implementationVersion = project.version.toString()
        tasks.withType<Jar>().configureEach {
            manifest.attributes("Implementation-Version" to implementationVersion)
            metaInf { from(rootProject.files("LICENSE", "NOTICE")) }
        }
    }
}
