rootProject.name = "axolotl"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

include(
    "axl-runtime",
    "axl-compiler",
    "axl-compiler-javac",
    "axl-smap",
    "axl-cli",
    "axl-gradle-plugin",
    "axl-maven-plugin",
    "axl-lsp",
    "axl-intellij",
    "benchmarks",
)
