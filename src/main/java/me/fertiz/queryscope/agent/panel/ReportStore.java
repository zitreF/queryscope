package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.data.RequestReport;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class ReportStore {

    private final int capacity;
    private final Deque<RequestReport> withSql = new ArrayDeque<>();
    private final Deque<RequestReport> withoutSql = new ArrayDeque<>();
    private final List<Consumer<RequestReport>> subscribers = new CopyOnWriteArrayList<>();

    public ReportStore(int capacity) {
        this.capacity = capacity;
    }

    public void add(RequestReport report) {
        synchronized (this) {
            Deque<RequestReport> reports = report.trace().queries().isEmpty() ? withoutSql : withSql;
            reports.addFirst(report);
            if (reports.size() > capacity) {
                reports.removeLast();
            }
        }
        subscribers.forEach(subscriber -> subscriber.accept(report));
    }

    public synchronized List<RequestReport> newestFirst() {
        return Stream.concat(withSql.stream(), withoutSql.stream())
                .sorted(Comparator.comparingLong((RequestReport report) -> report.trace().id()).reversed())
                .toList();
    }

    public synchronized Optional<RequestReport> find(long id) {
        return Stream.concat(withSql.stream(), withoutSql.stream())
                .filter(report -> report.trace().id() == id)
                .findFirst();
    }

    public synchronized void clear() {
        withSql.clear();
        withoutSql.clear();
    }

    public Runnable subscribe(Consumer<RequestReport> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }
}
