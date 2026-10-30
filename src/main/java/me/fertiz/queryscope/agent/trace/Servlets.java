package me.fertiz.queryscope.agent.trace;


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
    private static final int ASSUMED_STATUS = 200;
    private static final int FAILED_STATUS = 500;

    private static final ClassValue<Optional<RequestGetters>> REQUESTS = new ClassValue<>() {
        @Override
        protected Optional<RequestGetters> computeValue(Class<?> type) {
            try {
                return findServletInterface(type, "HttpServletRequest").map(RequestGetters::of);
            } catch (IllegalStateException e) {
                return Optional.empty();
            }
        }
    };
    private static final ClassValue<Optional<MethodHandle>> STATUSES = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> type) {
            try {
                return findServletInterface(type, "HttpServletResponse")
                        .map(api -> publicGetter(api, "getStatus", INT_GETTER));
            } catch (IllegalStateException e) {
                return Optional.empty();
            }
        }
    };

    private Servlets() {
    }

    public static boolean requestStarted(Object request) {
        if (request == null || UnitTracer.inRequestOrEntryTask()) {
            return false;
        }
        Optional<RequestGetters> access = REQUESTS.get(request.getClass());
        return access.isPresent() && access.get().isFirstDispatch(request) && access.get().startRequest(request);
    }

    public static void requestFinished(Object response, boolean failed) {
        UnitTracer.requestFinished(failed ? FAILED_STATUS : statusOf(response));
    }

    public static String[] inBothServletNamespaces(String simpleName) {
        return NAMESPACES.stream().map(namespace -> namespace + "." + simpleName).toArray(String[]::new);
    }

    private static int statusOf(Object response) {
        Optional<MethodHandle> getStatus = response == null ? Optional.empty() : STATUSES.get(response.getClass());
        return getStatus.isPresent() ? readStatus(getStatus.get(), response) : ASSUMED_STATUS;
    }

    private static Optional<Class<?>> findServletInterface(Class<?> type, String simpleName) {
        List<String> names = List.of(inBothServletNamespaces("http." + simpleName));
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

    private static MethodHandle publicGetter(Class<?> api, String name, MethodType type) {
        try {
            return MethodHandles.publicLookup().unreflect(api.getMethod(name)).asType(type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(api.getName() + " has no usable " + name + "()", e);
        }
    }

    private static int readStatus(MethodHandle getStatus, Object response) {
        try {
            return (int) getStatus.invokeExact(response);
        } catch (Throwable e) {
            return ASSUMED_STATUS;
        }
    }

    private static Object callGetter(MethodHandle getter, Object target) throws Throwable {
        return getter.invokeExact(target);
    }

    private record RequestGetters(MethodHandle method, MethodHandle uri, MethodHandle query,
                                  MethodHandle dispatcherType) {

        private static RequestGetters of(Class<?> api) {
            return new RequestGetters(
                    publicGetter(api, "getMethod", GETTER),
                    publicGetter(api, "getRequestURI", GETTER),
                    publicGetter(api, "getQueryString", GETTER),
                    publicGetter(api, "getDispatcherType", GETTER));
        }

        private boolean isFirstDispatch(Object request) {
            try {
                return "REQUEST".equals(String.valueOf(callGetter(dispatcherType, request)));
            } catch (Throwable e) {
                return false;
            }
        }

        private boolean startRequest(Object request) {
            try {
                return UnitTracer.requestStarted(
                        (String) callGetter(method, request), (String) callGetter(uri, request),
                        (String) callGetter(query, request));
            } catch (Throwable e) {
                return false;
            }
        }
    }
}
