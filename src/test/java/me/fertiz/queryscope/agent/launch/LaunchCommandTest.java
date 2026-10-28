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
    void asksForDeeperJfrStacksUnlessTheUserSetThemOrTheVmIsOpenJ9() {
        String deep = "-XX:FlightRecorderOptions=stackdepth=256";

        assertEquals(List.of("-Xmx1g", deep),
                LaunchCommand.withDeepJfrStacks(List.of("-Xmx1g"), "OpenJDK 64-Bit Server VM"));
        assertEquals(List.of("-XX:FlightRecorderOptions=stackdepth=64"),
                LaunchCommand.withDeepJfrStacks(List.of("-XX:FlightRecorderOptions=stackdepth=64"), "Java HotSpot(TM)"));
        assertEquals(List.of(), LaunchCommand.withDeepJfrStacks(List.of(), "Eclipse OpenJ9 VM"));
    }

    @Test
    void leavesTheTargetEmptyWhenOnlyOptionsAreGiven() {
        LaunchCommand command = LaunchCommand.parse("--port=9090", "--attach=1234");

        assertEquals(List.of(), command.target());
        assertEquals(List.of("port=9090", "attach=1234"), command.agentOptions());
    }

    @Test
    void rejectsAnIncompleteApplication() {
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
