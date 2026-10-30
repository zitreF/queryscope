package me.fertiz.queryscope.agent.methodtrace;

import me.fertiz.queryscope.profiled.DailyReportJob;
import me.fertiz.queryscope.profiled.ReportJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodTracerTest {

    private static final String WORKER = ReportJob.class.getName();

    private final List<String> installed = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();
    private final MethodTracer tracer = new MethodTracer(
            () -> new Class<?>[]{ReportJob.class, DailyReportJob.class, String.class},
            (className, methodName) -> {
                installed.add(className + "#" + methodName);
                return () -> removed.add(className + "#" + methodName);
            });

    @AfterEach
    void stopEverything() {
        tracer.snapshots().forEach(snapshot -> tracer.stop(snapshot.method()));
    }

    @Test
    void installsTheTraceAndRecordsCallsUntilStopped() {
        MethodTracer.StartedTrace started = tracer.start(WORKER + "#work");
        MethodTraces.called(WORKER + ".work", System.nanoTime(), false);

        assertEquals(new MethodTracer.StartedTrace(WORKER + ".work", 1), started);
        assertEquals(List.of(WORKER + "#work"), installed);
        assertEquals(1, tracer.snapshots().get(0).calls());

        assertTrue(tracer.stop(WORKER + ".work"));
        MethodTraces.called(WORKER + ".work", System.nanoTime(), false);

        assertEquals(List.of(WORKER + "#work"), removed);
        assertEquals(List.of(), tracer.snapshots());
        assertFalse(tracer.stop(WORKER + ".work"));
    }

    @Test
    void startsEachMethodOnce() {
        tracer.start(WORKER + "#work");
        tracer.start(" " + WORKER + "#work ");

        assertEquals(1, installed.size());
        assertEquals(1, tracer.snapshots().size());
    }

    @Test
    void waitsForClassesThatAreNotLoadedYet() {
        assertEquals(0, tracer.start("com.acme.Later#run").loadedClasses());
    }

    @Test
    void refusesWhatItCannotTrace() {
        assertThrows(IllegalArgumentException.class, () -> tracer.start("com.acme.Worker.work"));
        assertThrows(IllegalArgumentException.class, () -> tracer.start("java.lang.String#length"));
        assertThrows(IllegalArgumentException.class,
                () -> tracer.start("me.fertiz.queryscope.agent.trace.UnitTracer#queryStarted"));
        assertThrows(IllegalArgumentException.class, () -> tracer.start(WORKER + "#missing"));
        assertThrows(IllegalArgumentException.class, () -> tracer.start(WORKER + "#plan"));
        assertEquals(List.of(), installed);
    }

    @Test
    void pointsAtTheClassThatDeclaresAnInheritedMethod() {
        IllegalArgumentException inherited = assertThrows(IllegalArgumentException.class,
                () -> tracer.start(DailyReportJob.class.getName() + "#work"));
        IllegalArgumentException abstractMethod = assertThrows(IllegalArgumentException.class,
                () -> tracer.start(WORKER + "#plan"));

        assertEquals(DailyReportJob.class.getName() + " inherits work from " + WORKER + ", trace " + WORKER
                + "#work instead", inherited.getMessage());
        assertEquals(WORKER + " has no method plan with a body, trace a class that implements it",
                abstractMethod.getMessage());
    }

    @Test
    void tracesAtMostTwentyMethods() {
        for (int index = 0; index < MethodTracer.MAX_TRACED_METHODS; index++) {
            tracer.start("com.acme.Later#run" + index);
        }

        assertThrows(IllegalStateException.class, () -> tracer.start("com.acme.Later#oneTooMany"));
    }

}
