package me.fertiz.queryscope.agent.launch;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LaunchCommandTest {

    @Test
    void readsOptionsThenTheJarThenItsArguments() {
        LaunchCommand command = LaunchCommand.parse("--port=9090", "--slowQueryMs=250", "matmaniak.jar", "--server.port=8090");

        assertEquals(List.of("port=9090", "slowQueryMs=250"), command.agentOptions());
        assertEquals(List.of("-jar", "matmaniak.jar"), command.target());
        assertEquals(List.of("--server.port=8090"), command.applicationArguments());
    }

    @Test
    void acceptsAnExplicitJarOrAClasspathAndMainClass() {
        assertEquals(List.of("-jar", "app.jar"), LaunchCommand.parse("-jar", "app.jar").target());
        assertEquals(List.of("-cp", "lib/*", "com.acme.Main"),
                LaunchCommand.parse("--class-path", "lib/*", "com.acme.Main", "run").target());
    }

    @Test
    void turnsFlagsWithoutValueIntoTrue() {
        assertEquals(List.of("open=true"), LaunchCommand.parse("--open", "app.jar").agentOptions());
    }

    @Test
    void rejectsAMissingApplication() {
        assertThrows(IllegalArgumentException.class, () -> LaunchCommand.parse("--port=9090"));
        assertThrows(IllegalArgumentException.class, () -> LaunchCommand.parse("-cp", "lib/*"));
        assertThrows(IllegalArgumentException.class, () -> LaunchCommand.parse("-jar"));
    }

    @Test
    void startsTheApplicationWithTheAgentAndTheLaunchersJvmOptions() {
        LaunchCommand command = LaunchCommand.parse("--port=9090", "app.jar", "--debug");

        List<String> process = command.processCommand("java", Path.of("queryscope.jar"),
                List.of("-Xmx1g", "-agentlib:jdwp=transport=dt_socket,address=5005", "-Dspring.profiles.active=local"), 42);

        assertEquals(List.of("java", "-Xmx1g", "-Dspring.profiles.active=local", "-Dqueryscope.launcherPid=42",
                "-javaagent:queryscope.jar=port=9090", "-jar", "app.jar", "--debug"), process);
    }
}
