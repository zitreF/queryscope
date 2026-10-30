package me.fertiz.queryscope.agent.config;

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

        assertEquals(new AgentConfig(9090, true, 3, Duration.ofMillis(250), 1000, 50,
                Duration.ofMillis(100), List.of(), List.of(), List.of(), true, true, Duration.ofMillis(10), false, true),
                config);
    }

    @Test
    void remembersWhetherThePortWasGiven() {
        assertFalse(AgentConfig.DEFAULTS.portGiven());
        assertFalse(AgentConfig.parse("slowQueryMs=50").portGiven());
        assertTrue(AgentConfig.parse("port=7070").portGiven());
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
    void readsTheCallTreeOptions() {
        AgentConfig config = AgentConfig.parse("package=com.acme.shop,package=com.acme.billing,slowMethodMs=40");

        assertEquals(List.of("com.acme.shop", "com.acme.billing"), config.packages());
        assertEquals(Duration.ofMillis(40), config.slowMethodThreshold());
        assertTrue(config.callTree());
        assertFalse(AgentConfig.parse("callTree=false").callTree());
        assertFalse(AgentConfig.parse("profile=false").callTree());
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("package=com..acme"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("package=com.acme.*"));
    }

    @Test
    void readsTheSamplingOptions() {
        assertEquals(Duration.ofMillis(20), AgentConfig.parse("sampleMs=20").sampleInterval());
        assertTrue(AgentConfig.DEFAULTS.sampling());
        assertFalse(AgentConfig.parse("sampling=false").sampling());
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("sampleMs=5000"));
    }

    @Test
    void collectsEveryEntryMethod() {
        AgentConfig config = AgentConfig.parse("entry=com.acme.ImportJob#run,entry=com.acme.mail.Digest$Weekly#send");

        assertEquals(List.of("com.acme.ImportJob#run", "com.acme.mail.Digest$Weekly#send"), config.entryMethods());
    }

    @Test
    void collectsEveryTracedMethod() {
        AgentConfig config = AgentConfig.parse("trace=com.acme.OrderService#findAll,trace=com.acme.Mapper$Line#toDto");

        assertEquals(List.of("com.acme.OrderService#findAll", "com.acme.Mapper$Line#toDto"), config.traceMethods());
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("trace=com.acme.OrderService.findAll"));
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
