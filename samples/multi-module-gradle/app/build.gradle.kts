plugins {
    application
    id("org.axl.lang")
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass = "app.Main"
}
