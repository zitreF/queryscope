package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.data.RequestReport;
import me.fertiz.queryscope.agent.data.RequestTrace;
import me.fertiz.queryscope.agent.profile.Profile;
import me.fertiz.queryscope.agent.profile.SampleStore;

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
        Map<String, String> parameters = parameters(rawQuery);
        long thread = parameters.containsKey("thread") && !parameters.get("thread").equals("all")
                ? number(parameters, "thread")
                : Profile.Filter.ALL_THREADS;
        boolean appOnly = choice(parameters, "frames", "all", "app").equals("app");
        boolean includeNative = choice(parameters, "samples", "cpu", "all").equals("all");
        if (!parameters.containsKey("request")) {
            long maxSeconds = Math.max(1, samples.maxAgeMillis() / 1000);
            long seconds = parameters.containsKey("window") ? number(parameters, "window") : DEFAULT_WINDOW_SECONDS;
            long now = samples.now();
            return Optional.of(new Profile.Filter(now - Math.min(seconds, maxSeconds) * 1000, now, thread, appOnly,
                    includeNative, List.of()));
        }
        Optional<RequestReport> report = reports.find(number(parameters, "request"));
        if (report.isEmpty()) {
            return Optional.empty();
        }
        boolean endpoint = choice(parameters, "scope", "request", "endpoint").equals("endpoint");
        String name = report.get().trace().endpoint();
        List<RequestTrace> traces = endpoint
                ? reports.newestFirst().stream()
                        .map(RequestReport::trace)
                        .filter(trace -> trace.endpoint().equals(name))
                        .toList()
                : List.of(report.get().trace());
        long slack = samples.intervalMillis();
        List<Profile.Window> windows = traces.stream()
                .map(trace -> new Profile.Window(trace.threadId(), trace.startedAt().toEpochMilli(),
                        trace.endedAt().toEpochMilli() + slack))
                .toList();
        long from = windows.stream().mapToLong(Profile.Window::fromMillis).min().orElse(0);
        long to = windows.stream().mapToLong(Profile.Window::toMillis).max().orElse(0);
        return Optional.of(new Profile.Filter(from, to, thread, appOnly, includeNative, windows));
    }

    private static Map<String, String> parameters(String rawQuery) {
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

    private static long number(Map<String, String> parameters, String name) {
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

    private static String choice(Map<String, String> parameters, String name, String fallback, String other) {
        String value = parameters.getOrDefault(name, fallback);
        if (!value.equals(fallback) && !value.equals(other)) {
            throw new IllegalArgumentException(name + " must be " + fallback + " or " + other);
        }
        return value;
    }
}
