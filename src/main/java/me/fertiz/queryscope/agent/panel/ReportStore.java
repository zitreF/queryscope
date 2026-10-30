package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.findings.UnitReport;

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
    private final Deque<UnitReport> withSql = new ArrayDeque<>();
    private final Deque<UnitReport> withoutSql = new ArrayDeque<>();
    private final List<Consumer<UnitReport>> subscribers = new CopyOnWriteArrayList<>();

    public ReportStore(int capacity) {
        this.capacity = capacity;
    }

    public void add(UnitReport report) {
        synchronized (this) {
            Deque<UnitReport> reports = report.trace().queries().isEmpty() ? withoutSql : withSql;
            reports.addFirst(report);
            if (reports.size() > capacity) {
                reports.removeLast();
            }
        }
        subscribers.forEach(subscriber -> subscriber.accept(report));
    }

    public synchronized List<UnitReport> newestFirst() {
        return all()
                .sorted(Comparator.comparingLong((UnitReport report) -> report.trace().id()).reversed())
                .toList();
    }

    public synchronized Optional<UnitReport> find(long id) {
        return all()
                .filter(report -> report.trace().id() == id)
                .findFirst();
    }

    public synchronized void clear() {
        withSql.clear();
        withoutSql.clear();
    }

    public Runnable subscribe(Consumer<UnitReport> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    private Stream<UnitReport> all() {
        return Stream.concat(withSql.stream(), withoutSql.stream());
    }
}
