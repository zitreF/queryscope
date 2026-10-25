plugins {
    java
    alias(libs.plugins.shadow)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    implementation(libs.bytebuddy)
    compileOnly(libs.servlet.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.bytebuddy.agent)
    testImplementation(libs.servlet.api)
    testImplementation(libs.h2)
    testImplementation(libs.hikaricp)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Djdk.attach.allowAttachSelf=true")
}

tasks.jar {
    enabled = false
}

configurations.consumable("agentJar") {
    outgoing.artifact(tasks.shadowJar)
}

tasks.shadowJar {
    archiveFileName = "queryscope.jar"
    relocate("net.bytebuddy", "me.fertiz.queryscope.agent.shaded.bytebuddy")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    manifest {
        attributes(
            "Premain-Class" to "me.fertiz.queryscope.agent.QueryScopeAgent",
            "Can-Retransform-Classes" to "true",
        )
    }
}
