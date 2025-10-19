plugins {
    `java-library`
    `maven-publish`
    antlr
}

dependencies {
    antlr(libs.antlr)
    implementation(libs.antlr.runtime)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// The antlr plugin puts the whole tool on the api classpath; only the runtime is needed there.
configurations.api {
    setExtendsFrom(extendsFrom.filter { it.name != "antlr" })
}

tasks.generateGrammarSource {
    arguments = arguments + listOf("-visitor", "-no-listener", "-package", "org.axl.compiler.parse")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
