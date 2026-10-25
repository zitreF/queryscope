package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.data.RequestReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportStoreTest {

    private final ReportStore store = new ReportStore(2);

    @Test
    void keepsNewestReportsUpToCapacity() {
        store.add(Reports.plain(1, "/a"));
        store.add(Reports.plain(2, "/b"));
        store.add(Reports.plain(3, "/c"));

        assertEquals(List.of(3L, 2L), store.newestFirst().stream().map(report -> report.trace().id()).toList());
    }

    @Test
    void notifiesSubscribersUntilTheyUnsubscribe() {
        List<RequestReport> received = new ArrayList<>();
        Runnable unsubscribe = store.subscribe(received::add);

        store.add(Reports.plain(1, "/a"));
        unsubscribe.run();
        store.add(Reports.plain(2, "/b"));

        assertEquals(1, received.size());
    }

    @Test
    void clearRemovesEverything() {
        store.add(Reports.plain(1, "/a"));

        store.clear();

        assertTrue(store.newestFirst().isEmpty());
    }
}
