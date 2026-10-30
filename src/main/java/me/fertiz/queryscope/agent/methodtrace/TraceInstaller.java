package me.fertiz.queryscope.agent.methodtrace;

@FunctionalInterface
public interface TraceInstaller {

    Runnable installAndReturnRemover(String className, String methodName);
}
