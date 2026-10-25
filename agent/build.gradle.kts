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
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveFileName = "queryscope.jar"
    relocate("net.bytebuddy", "me.fertiz.queryscope.shaded.bytebuddy")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    manifest {
        attributes("Premain-Class" to "me.fertiz.queryscope.agent.QueryScopeAgent")
    }
}
