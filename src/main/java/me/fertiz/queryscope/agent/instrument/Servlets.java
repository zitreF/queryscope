package me.fertiz.queryscope.agent.instrument;

import me.fertiz.queryscope.agent.trace.RequestTracer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

public final class Servlets {

    static final List<String> NAMESPACES = List.of("jakarta.servlet", "javax.servlet");

    private static final MethodType GETTER = MethodType.methodType(Object.class, Object.class);
    private static final MethodType INT_GETTER = MethodType.methodType(int.class, Object.class);
    private static final int UNKNOWN_STATUS = 200;
    private static final int FAILED_STATUS = 500;

    private static final ClassValue<Optional<RequestAccess>> REQUESTS = new ClassValue<>() {
        @Override
        protected Optional<RequestAccess> computeValue(Class<?> type) {
            try {
                return api(type, "HttpServletRequest").map(RequestAccess::of);
            } catch (IllegalStateException e) {
                return Optional.empty();
            }
        }
    };
    private static final ClassValue<Optional<MethodHandle>> STATUSES = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> type) {
            try {
                return api(type, "HttpServletResponse").map(api -> getter(api, "getStatus", INT_GETTER));
            } catch (IllegalStateException e) {
                return Optional.empty();
            }
        }
    };

    private Servlets() {
    }

    public static boolean requestStarted(Object request) {
        if (request == null || RequestTracer.inForegroundUnit()) {
            return false;
        }
        return REQUESTS.get(request.getClass())
                .filter(access -> access.isFirstDispatch(request))
                .map(access -> access.start(request))
                .orElse(false);
    }

    public static void requestFinished(Object response, boolean failed) {
        int status = FAILED_STATUS;
        if (!failed && response != null) {
            status = STATUSES.get(response.getClass())
                    .map(getStatus -> status(getStatus, response))
                    .orElse(UNKNOWN_STATUS);
        }
        RequestTracer.requestFinished(status);
    }

    static List<String> typeNames(String simpleName) {
        return NAMESPACES.stream().map(namespace -> namespace + "." + simpleName).toList();
    }

    private static Optional<Class<?>> api(Class<?> type, String simpleName) {
        List<String> names = typeNames("http." + simpleName);
        Deque<Class<?>> pending = new ArrayDeque<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            pending.add(current);
        }
        while (!pending.isEmpty()) {
            Class<?> candidate = pending.poll();
            if (candidate.isInterface() && names.contains(candidate.getName())) {
                return Optional.of(candidate);
            }
            pending.addAll(List.of(candidate.getInterfaces()));
        }
        return Optional.empty();
    }

    private static MethodHandle getter(Class<?> api, String name, MethodType type) {
        try {
            return MethodHandles.publicLookup().unreflect(api.getMethod(name)).asType(type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(api.getName() + " has no usable " + name + "()", e);
        }
    }

    private static int status(MethodHandle getStatus, Object response) {
        try {
            return (int) getStatus.invokeExact(response);
        } catch (Throwable e) {
            return UNKNOWN_STATUS;
        }
    }

    private static Object invoke(MethodHandle getter, Object target) throws Throwable {
        return getter.invokeExact(target);
    }

    private record RequestAccess(MethodHandle method, MethodHandle uri, MethodHandle query, MethodHandle dispatcherType) {

        private static RequestAccess of(Class<?> api) {
            return new RequestAccess(
                    getter(api, "getMethod", GETTER),
                    getter(api, "getRequestURI", GETTER),
                    getter(api, "getQueryString", GETTER),
                    getter(api, "getDispatcherType", GETTER));
        }

        private boolean isFirstDispatch(Object request) {
            try {
                return "REQUEST".equals(String.valueOf(invoke(dispatcherType, request)));
            } catch (Throwable e) {
                return false;
            }
        }

        private boolean start(Object request) {
            try {
                return RequestTracer.requestStarted(
                        (String) invoke(method, request), (String) invoke(uri, request), (String) invoke(query, request));
            } catch (Throwable e) {
                return false;
            }
        }
    }
}
