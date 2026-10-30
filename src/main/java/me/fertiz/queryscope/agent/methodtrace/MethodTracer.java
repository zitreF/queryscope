package me.fertiz.queryscope.agent.methodtrace;

import me.fertiz.queryscope.agent.appcode.CallSites;
import me.fertiz.queryscope.agent.config.AgentConfig;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class MethodTracer {

    public static final int MAX_TRACED_METHODS = 20;

    private static final List<String> UNTRACEABLE_PACKAGES = List.of(
            "java.", "jdk.", "sun.", "com.sun.", "net.bytebuddy.", CallSites.AGENT_PACKAGE_PREFIX);

    private final Supplier<Class<?>[]> loadedClasses;
    private final TraceInstaller installer;
    private final Map<String, Runnable> removers = new LinkedHashMap<>();

    public MethodTracer(Supplier<Class<?>[]> loadedClasses, TraceInstaller installer) {
        this.loadedClasses = loadedClasses;
        this.installer = installer;
    }

    public synchronized StartedTrace start(String reference) {
        String checked = reference.trim();
        if (!AgentConfig.isMethodReference(checked)) {
            throw new IllegalArgumentException("Name the method as package.Class#method, like "
                    + "com.acme.OrderService#findAll, got '" + checked + "'");
        }
        int hash = checked.indexOf('#');
        String className = checked.substring(0, hash);
        String methodName = checked.substring(hash + 1);
        String method = className + "." + methodName;
        List<Class<?>> loaded = loadedClassesNamed(className);
        if (removers.containsKey(method)) {
            return new StartedTrace(method, loaded.size());
        }
        if (UNTRACEABLE_PACKAGES.stream().anyMatch(className::startsWith)
                || loaded.stream().anyMatch(MethodTracer::isPartOfTheJdk)) {
            throw new IllegalArgumentException("QueryScope cannot trace " + className
                    + ": classes of the JDK and of QueryScope stay as they are");
        }
        for (Class<?> type : loaded) {
            if (!declaresConcreteMethod(type, methodName)) {
                throw new IllegalArgumentException(className + " has no method " + methodName + " with a body");
            }
        }
        if (removers.size() >= MAX_TRACED_METHODS) {
            throw new IllegalStateException("QueryScope traces at most " + MAX_TRACED_METHODS
                    + " methods at a time, stop one first");
        }
        MethodTraces.startRecording(method);
        try {
            removers.put(method, installer.installAndReturnRemover(className, methodName));
        } catch (RuntimeException | LinkageError e) {
            MethodTraces.stopRecording(method);
            throw e;
        }
        return new StartedTrace(method, loaded.size());
    }

    public synchronized boolean stop(String method) {
        Runnable remover = removers.remove(method);
        if (remover == null) {
            return false;
        }
        MethodTraces.stopRecording(method);
        remover.run();
        return true;
    }

    public synchronized List<TracedMethod.Snapshot> snapshots() {
        return MethodTraces.recorded(new ArrayList<>(removers.keySet())).stream()
                .map(TracedMethod::snapshot)
                .toList();
    }

    private List<Class<?>> loadedClassesNamed(String className) {
        return Arrays.stream(loadedClasses.get()).filter(type -> type.getName().equals(className)).toList();
    }

    private static boolean isPartOfTheJdk(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null || loader == ClassLoader.getPlatformClassLoader();
    }

    private static boolean declaresConcreteMethod(Class<?> type, String methodName) {
        try {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(methodName) && !Modifier.isAbstract(method.getModifiers())
                        && !Modifier.isNative(method.getModifiers())) {
                    return true;
                }
            }
            return false;
        } catch (LinkageError e) {
            return true;
        }
    }

    public record StartedTrace(String method, int loadedClasses) {
    }
}
