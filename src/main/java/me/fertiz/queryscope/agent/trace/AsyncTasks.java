package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.bootstrap.AsyncBridge;

public final class AsyncTasks {

    private AsyncTasks() {
    }

    public static void connect() {
        AsyncBridge.connect(
                UnitTracer::asyncOwnerOfThisThread,
                (task, owner) -> UnitTracer.asyncTaskSubmitted(task, (ActiveUnit) owner),
                UnitTracer::asyncTaskStarted,
                UnitTracer::asyncTaskFinished);
    }
}
