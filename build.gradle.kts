plugins {
    java
    alias(libs.plugins.shadow)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.compileJava {
    options.release = 17
}

tasks.compileTestJava {
    options.compilerArgs.add("-parameters")
}

dependencies {
    implementation(libs.bytebuddy)
    compileOnly(libs.servlet.api)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.bytebuddy.agent)
    testImplementation(libs.javax.servlet.api)
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveFileName = "queryscope.jar"
    relocate("net.bytebuddy", "me.fertiz.queryscope.agent.shaded.bytebuddy")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    manifest {
        attributes(
            "Premain-Class" to "me.fertiz.queryscope.agent.QueryScopeAgent",
            "Agent-Class" to "me.fertiz.queryscope.agent.QueryScopeAgent",
            "Main-Class" to "me.fertiz.queryscope.agent.launch.QueryScopeLauncher",
            "Can-Retransform-Classes" to "true",
        )
    }
}

val agentJar = tasks.shadowJar.flatMap { it.archiveFile }
val demoClasspath = sourceSets.test.get().runtimeClasspath - sourceSets.main.get().output

tasks.test {
    useJUnitPlatform()
    inputs.file(agentJar).withPropertyName("agentJar")
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Djdk.attach.allowAttachSelf=true")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dqueryscope.agentJar=${agentJar.get().asFile.absolutePath}",
            "-Dqueryscope.demoClasspath=${demoClasspath.asPath}",
        )
    })
}

tasks.register<JavaExec>("runDemo") {
    group = "application"
    description = "Runs the demo shop from the tests with the QueryScope agent attached."
    dependsOn(tasks.shadowJar)
    classpath = demoClasspath
    mainClass = "me.fertiz.queryscope.demo.DemoApplication"
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${agentJar.get().asFile.absolutePath}")
    })
}
