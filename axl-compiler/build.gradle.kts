plugins {
    `java-library`
    `maven-publish`
    antlr
    idea
}

dependencies {
    antlr(libs.antlr)
    api(libs.antlr.runtime)
    // The parts of the compiler written in Axolotl use its runtime and nullness annotations.
    api(project(":axl-runtime"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// The antlr plugin puts the whole tool on the api classpath; only the runtime is needed there.
configurations.api {
    setExtendsFrom(extendsFrom.filter { it.name != "antlr" })
}

// Java generated from src/main/axl by an earlier compiler; see docs/self-hosting.md.
sourceSets.main {
    java.srcDir("bootstrap")
}

// IntelliJ then warns before the snapshot is edited by hand.
idea {
    module {
        generatedSourceDirs.add(file("bootstrap"))
    }
}

tasks.generateGrammarSource {
    arguments = arguments + listOf("-visitor", "-no-listener")
    packageName = "org.axl.compiler.parse"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
