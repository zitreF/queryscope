package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.profiler.Profile;
import me.fertiz.queryscope.agent.profiler.SampleStore;
import me.fertiz.queryscope.agent.trace.UnitTrace;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ProfileQuery {

    private static final long DEFAULT_WINDOW_SECONDS = 300;

    private ProfileQuery() {
    }

    static Optional<Profile.Filter> parse(String rawQuery, ReportStore reports, SampleStore samples) {
        Map<String, String> parameters = parseQueryString(rawQuery);
        long thread = parameters.getOrDefault("thread", "all").equals("all")
                ? Profile.Filter.ALL_THREADS
                : parsePositiveLong(parameters, "thread");
        boolean appOnly = parseChoice(parameters, "frames", "all", "app").equals("app");
        boolean includeNative = parseChoice(parameters, "samples", "cpu", "all").equals("all");
        if (!parameters.containsKey("request")) {
            return Optional.of(recentFilter(parameters, samples, thread, appOnly, includeNative));
        }
        return reports.find(parsePositiveLong(parameters, "request"))
                .map(report -> requestFilter(parameters, report, reports, samples, thread, appOnly, includeNative));
    }

    private static Profile.Filter recentFilter(Map<String, String> parameters, SampleStore samples, long thread,
                                               boolean appOnly, boolean includeNative) {
        long maxSeconds = Math.max(1, samples.maxAgeMillis() / 1000);
        long seconds = parameters.containsKey("window")
                ? parsePositiveLong(parameters, "window")
                : DEFAULT_WINDOW_SECONDS;
        long now = samples.now();
        return new Profile.Filter(now - Math.min(seconds, maxSeconds) * 1000, now, thread, appOnly, includeNative,
                List.of());
    }

    private static Profile.Filter requestFilter(Map<String, String> parameters, UnitReport report,
                                                ReportStore reports, SampleStore samples, long thread,
                                                boolean appOnly, boolean includeNative) {
        boolean wholeEndpoint = parseChoice(parameters, "scope", "request", "endpoint").equals("endpoint");
        String endpoint = report.trace().endpoint();
        List<UnitTrace> traces = wholeEndpoint
                ? reports.newestFirst().stream()
                        .map(UnitReport::trace)
                        .filter(trace -> trace.endpoint().equals(endpoint))
                        .toList()
                : List.of(report.trace());
        long slack = samples.intervalMillis();
        List<Profile.Window> windows = traces.stream()
                .map(trace -> new Profile.Window(trace.threadId(), trace.startedAt().toEpochMilli(),
                        trace.endedAt().toEpochMilli() + slack))
                .toList();
        long from = windows.stream().mapToLong(Profile.Window::fromMillis).min().orElse(0);
        long to = windows.stream().mapToLong(Profile.Window::toMillis).max().orElse(0);
        return new Profile.Filter(from, to, thread, appOnly, includeNative, windows);
    }

    private static Map<String, String> parseQueryString(String rawQuery) {
        Map<String, String> parameters = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return parameters;
        }
        for (String pair : rawQuery.split("&")) {
            String[] keyAndValue = pair.split("=", 2);
            parameters.put(URLDecoder.decode(keyAndValue[0], StandardCharsets.UTF_8),
                    keyAndValue.length == 2 ? URLDecoder.decode(keyAndValue[1], StandardCharsets.UTF_8) : "");
        }
        return parameters;
    }

    private static long parsePositiveLong(Map<String, String> parameters, String name) {
        try {
            long value = Long.parseLong(parameters.get(name));
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private static String parseChoice(Map<String, String> parameters, String name, String fallback, String other) {
        String value = parameters.getOrDefault(name, fallback);
        if (!value.equals(fallback) && !value.equals(other)) {
            throw new IllegalArgumentException(name + " must be " + fallback + " or " + other);
        }
        return value;
    }
}
