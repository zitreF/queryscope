package me.fertiz.queryscope.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentConfigTest {

    @Test
    void usesDefaultsWithoutArguments() {
        assertEquals(AgentConfig.DEFAULTS, AgentConfig.parse(null));
        assertEquals(AgentConfig.DEFAULTS, AgentConfig.parse(" "));
    }

    @Test
    void readsCommaSeparatedOptions() {
        AgentConfig config = AgentConfig.parse("port=9090, nPlusOneThreshold=3,slowQueryMs=250");

        assertEquals(new AgentConfig(9090, 3, Duration.ofMillis(250)), config);
    }

    @Test
    void rejectsUnknownOptionsAndInvalidNumbers() {
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("slowQuery=250"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("slowQueryMs=0"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("nPlusOneThreshold=many"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("port=70000"));
    }
}
