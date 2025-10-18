rootProject.name = "axolotl"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
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
)
