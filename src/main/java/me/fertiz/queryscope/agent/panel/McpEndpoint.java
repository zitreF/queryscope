package me.fertiz.queryscope.agent.panel;

import com.sun.net.httpserver.HttpExchange;
import me.fertiz.queryscope.agent.appcode.AppPackages;
import me.fertiz.queryscope.agent.console.AiReport;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.http.JsonReader;
import me.fertiz.queryscope.agent.http.JsonWriter;
import me.fertiz.queryscope.agent.methodtrace.MethodTracer;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static me.fertiz.queryscope.agent.http.HttpSupport.JSON;
import static me.fertiz.queryscope.agent.http.HttpSupport.readBody;
import static me.fertiz.queryscope.agent.http.HttpSupport.requireJsonFromSameOrigin;
import static me.fertiz.queryscope.agent.http.HttpSupport.requireMethod;
import static me.fertiz.queryscope.agent.http.HttpSupport.respond;
import static me.fertiz.queryscope.agent.http.HttpSupport.sendStatus;

final class McpEndpoint {

    private static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final int MAX_BODY = 64 * 1024;
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int DEFAULT_REQUESTS = 20;
    private static final int MAX_REQUESTS = 200;
    private static final String INSTRUCTIONS = "QueryScope watches this Java application from inside its JVM: every "
            + "SQL query with its call site, the call tree of the application's methods, CPU samples and the methods "
            + "being traced. Start with get_report, open one request or task with get_request, and use trace_method "
            + "to time a method across all its calls while the application runs.";
    private static final List<Tool> TOOLS = List.of(
            new Tool("get_report", "QueryScope's report of the application in Markdown: findings such as N+1 queries "
                    + "with their SQL, call site and where they were seen, endpoints by time in the database, hot "
                    + "methods from CPU samples and traced methods.", List.of()),
            new Tool("list_requests", "The newest requests and tasks QueryScope keeps, one per line with their id, "
                    + "time, number of queries and findings.", List.of(
                    new Parameter("limit", "integer", "How many to list, 20 by default, at most 200", false),
                    new Parameter("onlyWithFindings", "boolean", "List only requests and tasks with findings", false))),
            new Tool("get_request", "Everything about one request or task as JSON: each query with its SQL, "
                    + "parameters, time, rows and call site, its findings and the call tree of the application's "
                    + "methods with the queries each one ran.", List.of(
                    new Parameter("id", "integer", "The id from get_report or list_requests", true))),
            new Tool("trace_method", "Starts recording every call of one method while the application runs, in the "
                    + "application or in a library, with its count, percentiles and slowest calls.", List.of(
                    new Parameter("method", "string", "The class and method, like com.acme.OrderService#findAll",
                            true))),
            new Tool("list_traces", "The traced methods as JSON: calls, failures, average, p50, p95, p99, maximum "
                    + "and their slowest and latest calls with the request or task each ran in.", List.of()),
            new Tool("stop_trace", "Stops tracing a method and removes the recording.", List.of(
                    new Parameter("method", "string", "The traced method as list_traces names it, like "
                            + "com.acme.OrderService.findAll", true))));

    private final ReportStore store;
    private final AppPackages callTreePackages;
    private final MethodTracer tracer;
    private final Supplier<String> report;

    McpEndpoint(ReportStore store, AppPackages callTreePackages, MethodTracer tracer, Supplier<String> report) {
        this.store = store;
        this.callTreePackages = callTreePackages;
        this.tracer = tracer;
        this.report = report;
    }

    void serve(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST") || !requireJsonFromSameOrigin(exchange)) {
            return;
        }
        Object message;
        try {
            message = JsonReader.parse(readBody(exchange, MAX_BODY));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, JSON, errorResponse(null, PARSE_ERROR, e.getMessage()));
            return;
        }
        if (!(message instanceof Map<?, ?> request) || !(request.get("method") instanceof String method)) {
            respond(exchange, 400, JSON, errorResponse(null, INVALID_REQUEST, "Send one JSON-RPC 2.0 request"));
            return;
        }
        if (!request.containsKey("id")) {
            sendStatus(exchange, 202);
            return;
        }
        respond(exchange, 200, JSON, answer(request.get("id"), method, mapOrEmpty(request.get("params"))));
    }

    String answer(Object id, String method, Map<?, ?> params) {
        return switch (method) {
            case "initialize" -> response(id, json -> writeInitializeResult(json, params));
            case "ping" -> response(id, json -> json.beginObject().endObject());
            case "tools/list" -> response(id, McpEndpoint::writeTools);
            case "tools/call" -> callTool(id, params);
            default -> errorResponse(id, METHOD_NOT_FOUND, "QueryScope has no method " + method);
        };
    }

    private String callTool(Object id, Map<?, ?> params) {
        Object name = params.get("name");
        if (TOOLS.stream().noneMatch(tool -> tool.name().equals(name))) {
            return errorResponse(id, INVALID_PARAMS, "QueryScope has no tool " + name);
        }
        Map<?, ?> arguments = mapOrEmpty(params.get("arguments"));
        String text;
        boolean failed = false;
        try {
            text = runTool((String) name, arguments);
        } catch (IllegalArgumentException | IllegalStateException e) {
            text = e.getMessage();
            failed = true;
        }
        String resultText = text;
        boolean isError = failed;
        return response(id, json -> json.beginObject()
                .name("content").beginArray()
                .beginObject().name("type").value("text").name("text").value(resultText).endObject()
                .endArray()
                .name("isError").value(isError)
                .endObject());
    }

    private String runTool(String name, Map<?, ?> arguments) {
        return switch (name) {
            case "get_report" -> report.get();
            case "list_requests" -> AiReport.requestList(store.newestFirst(), limitArgument(arguments),
                    Boolean.TRUE.equals(arguments.get("onlyWithFindings")));
            case "get_request" -> requestText(idArgument(arguments));
            case "trace_method" -> {
                MethodTracer.StartedTrace started = tracer.start(stringArgument(arguments, "method"));
                yield "QueryScope traces " + started.method() + " now"
                        + (started.loadedClasses() == 0 ? ", once its class is loaded." : ".");
            }
            case "list_traces" -> MethodTraceJson.tracesToJson(tracer.snapshots(), MethodTracer.MAX_TRACED_METHODS);
            case "stop_trace" -> {
                String method = stringArgument(arguments, "method");
                if (!tracer.stop(method)) {
                    throw new IllegalArgumentException(method + " is not traced, list_traces names the traced methods");
                }
                yield "QueryScope stopped tracing " + method + ".";
            }
            default -> throw new IllegalArgumentException("QueryScope has no tool " + name);
        };
    }

    private String requestText(long id) {
        Optional<UnitReport> found = store.find(id);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("QueryScope keeps no request or task " + id
                    + " any more, list_requests shows the ones it has");
        }
        UnitReport unit = found.get();
        String text = ReportJson.reportToJson(unit);
        return unit.trace().callTree().methodCount() == 0 ? text
                : text + "\n\nCall tree:\n" + ReportJson.callTreeToJson(unit, callTreePackages.names());
    }

    private static int limitArgument(Map<?, ?> arguments) {
        Object limit = arguments.get("limit");
        if (limit == null) {
            return DEFAULT_REQUESTS;
        }
        if (!(limit instanceof Long number) || number < 1 || number > MAX_REQUESTS) {
            throw new IllegalArgumentException("limit needs a whole number from 1 to " + MAX_REQUESTS
                    + ", got " + limit);
        }
        return number.intValue();
    }

    private static long idArgument(Map<?, ?> arguments) {
        if (!(arguments.get("id") instanceof Long id)) {
            throw new IllegalArgumentException("id needs the number of a request or task, like 17");
        }
        return id;
    }

    private static String stringArgument(Map<?, ?> arguments, String name) {
        if (!(arguments.get(name) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static void writeInitializeResult(JsonWriter json, Map<?, ?> params) {
        Object requested = params.get("protocolVersion");
        String version = PROTOCOL_VERSIONS.contains(requested) ? (String) requested : PROTOCOL_VERSIONS.get(0);
        json.beginObject()
                .name("protocolVersion").value(version)
                .name("capabilities").beginObject()
                .name("tools").beginObject().name("listChanged").value(false).endObject()
                .endObject()
                .name("serverInfo").beginObject().name("name").value("queryscope").name("version").value("1")
                .endObject()
                .name("instructions").value(INSTRUCTIONS)
                .endObject();
    }

    private static void writeTools(JsonWriter json) {
        json.beginObject().name("tools").beginArray();
        for (Tool tool : TOOLS) {
            json.beginObject()
                    .name("name").value(tool.name())
                    .name("description").value(tool.description())
                    .name("inputSchema").beginObject()
                    .name("type").value("object")
                    .name("properties").beginObject();
            for (Parameter parameter : tool.parameters()) {
                json.name(parameter.name()).beginObject()
                        .name("type").value(parameter.type())
                        .name("description").value(parameter.description())
                        .endObject();
            }
            json.endObject().name("required").beginArray();
            tool.parameters().stream().filter(Parameter::required).forEach(parameter -> json.value(parameter.name()));
            json.endArray().endObject().endObject();
        }
        json.endArray().endObject();
    }

    private static String response(Object id, Consumer<JsonWriter> result) {
        JsonWriter json = new JsonWriter().beginObject().name("jsonrpc").value("2.0").name("id");
        writeId(json, id);
        json.name("result");
        result.accept(json);
        return json.endObject().toString();
    }

    private static String errorResponse(Object id, int code, String message) {
        JsonWriter json = new JsonWriter().beginObject().name("jsonrpc").value("2.0").name("id");
        writeId(json, id);
        return json.name("error").beginObject()
                .name("code").value(code)
                .name("message").value(message)
                .endObject().endObject().toString();
    }

    private static void writeId(JsonWriter json, Object id) {
        if (id instanceof Long number) {
            json.value(number);
        } else if (id instanceof Double number) {
            json.value(number);
        } else if (id instanceof String text) {
            json.value(text);
        } else {
            json.nullValue();
        }
    }

    private static Map<?, ?> mapOrEmpty(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private record Tool(String name, String description, List<Parameter> parameters) {
    }

    private record Parameter(String name, String type, String description, boolean required) {
    }
}
