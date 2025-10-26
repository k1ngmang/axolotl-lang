plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":axl-compiler"))
    implementation(libs.asm)
    compileOnly(platform(libs.junit.bom))
    compileOnly(libs.junit.jupiter.api)
    testImplementation(project(":axl-compiler-javac"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.launcher)
}

tasks.test {
    @Suppress("UNCHECKED_CAST")
    jvmArgs(rootProject.extra["errorProneJvmArgs"] as List<String>)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
