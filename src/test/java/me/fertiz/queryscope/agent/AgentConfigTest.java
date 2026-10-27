package me.fertiz.queryscope.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {

    @Test
    void usesDefaultsWithoutArguments() {
        assertEquals(AgentConfig.DEFAULTS, AgentConfig.parse(null));
        assertEquals(AgentConfig.DEFAULTS, AgentConfig.parse(" "));
    }

    @Test
    void readsCommaSeparatedOptions() {
        AgentConfig config = AgentConfig.parse(
                "port=9090, nPlusOneThreshold=3,slowQueryMs=250,largeResultRows=1000,maxQueries=50");

        assertEquals(new AgentConfig(9090, 3, Duration.ofMillis(250), 1000, 50, List.of(), false, true), config);
    }

    @Test
    void readsFlagsWithOrWithoutValue() {
        assertTrue(AgentConfig.parse("open").openPanel());
        assertTrue(AgentConfig.parse("open=true").openPanel());
        assertFalse(AgentConfig.parse("summary=false").printSummary());
        assertTrue(AgentConfig.DEFAULTS.printSummary());
        assertFalse(AgentConfig.DEFAULTS.openPanel());
    }

    @Test
    void collectsEveryEntryMethod() {
        AgentConfig config = AgentConfig.parse("entry=com.acme.ImportJob#run,entry=com.acme.mail.Digest$Weekly#send");

        assertEquals(List.of("com.acme.ImportJob#run", "com.acme.mail.Digest$Weekly#send"), config.entryMethods());
    }

    @Test
    void rejectsUnknownOptionsAndInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("slowQuery=250"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("slowQueryMs=0"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("nPlusOneThreshold=many"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("port=70000"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("entry=com.acme.ImportJob.run"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("summary=no"));
    }
}
