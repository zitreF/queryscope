package me.fertiz.queryscope.agent.profiler;

import me.fertiz.queryscope.agent.calltree.AppPackages;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileTest {

    private static final long NOW = Instant.parse("2026-10-28T10:00:00Z").toEpochMilli();
    private static final AppPackages ACME = AppPackages.of(List.of("com.acme"));
    private static final String RUN = "java.lang.Thread.run";
    private static final String RENDER = "com.acme.ReportService.render";
    private static final String FORMAT = "com.acme.ReportService.format";
    private static final String APPEND = "java.lang.StringBuilder.append";

    private final SampleStore store = new SampleStore(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), 100,
            Duration.ofMinutes(5));

    @Test
    void mergesStacksIntoATreeWithTotalAndSelfSamples() {
        sample(1, NOW, RUN, RENDER, APPEND);
        sample(1, NOW, RUN, RENDER, APPEND);
        sample(1, NOW, RUN, RENDER, FORMAT);

        Profile profile = Profile.build(store, ACME, everything(false));

        Profile.Node render = only(only(profile.root()));
        assertEquals(3, profile.samples());
        assertEquals(3, profile.root().total());
        assertEquals(RENDER, render.method());
        assertTrue(render.app());
        assertEquals(List.of(APPEND, FORMAT), render.children().stream().map(Profile.Node::method).toList());
        assertEquals(2, render.children().get(0).self());
        assertFalse(render.children().get(0).app());
    }

    @Test
    void keepsOnlyYourFramesAndTheLeafWhenAskedForYourCode() {
        sample(1, NOW, RUN, RENDER, "org.lib.Template.apply", APPEND);
        sample(1, NOW, RUN, "org.lib.Scheduler.tick");

        Profile profile = Profile.build(store, ACME, everything(true));

        assertEquals(List.of(RENDER, Profile.OUTSIDE_YOUR_CODE),
                profile.root().children().stream().map(Profile.Node::method).toList());
        assertEquals(APPEND, only(profile.root().children().get(0)).method());
        assertEquals("org.lib.Scheduler.tick", only(profile.root().children().get(1)).method());
    }

    @Test
    void ranksHotMethodsBySelfAndYourMethodsByTheTimeSpentUnderThem() {
        sample(1, NOW, RUN, RENDER, APPEND);
        sample(1, NOW, RUN, RENDER, FORMAT, APPEND);

        List<Profile.HotMethod> hot = Profile.build(store, ACME, everything(false)).hot();

        assertEquals(new Profile.HotMethod(APPEND, false, 2, 2, 0), hot.get(0));
        assertTrue(hot.contains(new Profile.HotMethod(RENDER, true, 0, 2, 1)), hot.toString());
        assertTrue(hot.contains(new Profile.HotMethod(FORMAT, true, 0, 1, 1)), hot.toString());
    }

    @Test
    void filtersByThreadTimeWindowsAndNativeSamples() {
        sample(1, NOW - 50, RUN, RENDER);
        sample(2, NOW - 50, RUN, FORMAT);
        sample(1, NOW - 5_000, RUN, FORMAT);
        store.add(NOW - 50, 1, "worker", false, false, List.of(RUN, "sun.nio.ch.SocketDispatcher.read0"));

        Profile.Filter request = new Profile.Filter(NOW - 100, NOW, Profile.Filter.ALL_THREADS, false, false,
                List.of(new Profile.Window(1, NOW - 100, NOW)));
        Profile.Filter withNative = new Profile.Filter(NOW - 100, NOW, 1, false, true, List.of());

        assertEquals(1, Profile.build(store, ACME, request).samples());
        assertEquals(List.of(1L), Profile.build(store, ACME, request).threads().stream().map(Profile.ThreadSamples::id).toList());
        assertEquals(2, Profile.build(store, ACME, withNative).samples());
    }

    @Test
    void countsSamplesInsideQueryScopeApartFromTheApp() {
        sample(1, NOW, RUN, RENDER);
        sample(1, NOW, RUN, "me.fertiz.queryscope.agent.panel.PanelServer.serveProfile");

        Profile profile = Profile.build(store, ACME, everything(false));

        assertEquals(1, profile.samples());
        assertEquals(1, profile.agentSamples());
    }

    @Test
    void threadListLeavesOutQueryScopeSamples() {
        sample(1, NOW, RUN, RENDER);
        sample(2, NOW, RUN, "me.fertiz.queryscope.agent.AgentInstaller.install");
        sample(2, NOW, RUN, "me.fertiz.queryscope.agent.AgentInstaller.install");

        Profile all = Profile.build(store, ACME, everything(false));
        Profile secondThread = Profile.build(store, ACME,
                new Profile.Filter(0, Long.MAX_VALUE, 2, false, false, List.of()));

        assertEquals(List.of(1L), all.threads().stream().map(Profile.ThreadSamples::id).toList());
        assertEquals(2, all.agentSamples());
        assertEquals(0, secondThread.samples());
        assertEquals(2, secondThread.agentSamples());
    }

    @Test
    void putsTruncatedStacksUnderTheirOwnRoot() {
        store.add(NOW, 1, "worker", true, true, List.of(RENDER, APPEND));

        Profile profile = Profile.build(store, ACME, everything(false));

        assertEquals(Profile.TRUNCATED, only(profile.root()).method());
    }

    private void sample(long thread, long millis, String... frames) {
        store.add(millis, thread, "thread " + thread, true, false, List.of(frames));
    }

    private static Profile.Filter everything(boolean appOnly) {
        return new Profile.Filter(0, Long.MAX_VALUE, Profile.Filter.ALL_THREADS, appOnly, false, List.of());
    }

    private static Profile.Node only(Profile.Node node) {
        assertEquals(1, node.children().size(), node.children().toString());
        return node.children().get(0);
    }
}
