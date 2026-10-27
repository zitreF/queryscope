package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.data.RequestReport;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class ReportStore {

    private final int capacity;
    private final Deque<RequestReport> reports = new ArrayDeque<>();
    private final List<Consumer<RequestReport>> subscribers = new CopyOnWriteArrayList<>();

    public ReportStore(int capacity) {
        this.capacity = capacity;
    }

    public void add(RequestReport report) {
        synchronized (reports) {
            reports.addFirst(report);
            if (reports.size() > capacity) {
                reports.removeLast();
            }
        }
        subscribers.forEach(subscriber -> subscriber.accept(report));
    }

    public List<RequestReport> newestFirst() {
        synchronized (reports) {
            return List.copyOf(reports);
        }
    }

    public Optional<RequestReport> find(long id) {
        synchronized (reports) {
            return reports.stream().filter(report -> report.trace().id() == id).findFirst();
        }
    }

    public void clear() {
        synchronized (reports) {
            reports.clear();
        }
    }

    public Runnable subscribe(Consumer<RequestReport> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }
}
