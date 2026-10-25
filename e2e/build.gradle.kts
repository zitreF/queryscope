plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val agent = configurations.dependencyScope("agent")
val demoApp = configurations.dependencyScope("demoApp")
val agentJar = configurations.resolvable("agentJarFile") {
    extendsFrom(agent.get())
}
val demoJar = configurations.resolvable("demoJarFile") {
    extendsFrom(demoApp.get())
}

dependencies {
    "agent"(project(path = ":agent", configuration = "agentJar"))
    "demoApp"(project(path = ":demo-app", configuration = "demoJar"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jackson.databind)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    inputs.files(agentJar, demoJar).withPropertyName("jars")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dqueryscope.agentJar=${agentJar.get().singleFile.absolutePath}",
            "-Dqueryscope.demoJar=${demoJar.get().singleFile.absolutePath}",
        )
    })
}
