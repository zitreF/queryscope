package me.fertiz.queryscope.agent.launch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HubCommandTest {

    @Test
    void startsTheHubOnTheDefaultPortAndOpensTheBrowser() {
        assertEquals(new HubCommand(HubCommand.DEFAULT_PORT, true, HubCommand.NO_PID, List.of()),
                HubCommand.parse(List.of()));
    }

    @Test
    void keepsItsOwnOptionsAndPassesTheRestToTheAgent() {
        HubCommand command = HubCommand.parse(List.of("port=9000", "open=false", "attach=4321", "slowQueryMs=50"));

        assertEquals(new HubCommand(9000, false, 4321, List.of("slowQueryMs=50")), command);
    }

    @Test
    void rejectsBadNumbersAndUnknownAgentOptions() {
        assertThrows(IllegalArgumentException.class, () -> HubCommand.parse(List.of("port=70000")));
        assertThrows(IllegalArgumentException.class, () -> HubCommand.parse(List.of("attach=me")));
        assertThrows(IllegalArgumentException.class, () -> HubCommand.parse(List.of("slowQuery=50")));
    }
}
