package me.fertiz.queryscope.agent.trace;

import java.util.List;
import java.util.Optional;

final class CallSites {

    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final List<String> FRAMEWORK_PACKAGES = List.of(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.",
            "org.springframework.", "org.hibernate.", "org.apache.", "org.eclipse.", "org.jboss.",
            "com.zaxxer.", "org.h2.", "org.postgresql.", "com.mysql.", "org.mariadb.", "oracle.",
            "com.microsoft.sqlserver.", "com.fasterxml.", "tools.jackson.", "io.micrometer.",
            "me.fertiz.queryscope.agent.");

    private CallSites() {
    }

    static String current() {
        return applicationFrame()
                .map(frame -> frame.getClassName() + "." + frame.getMethodName()
                        + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")")
                .orElse(null);
    }

    static String entryName() {
        return applicationFrame()
                .map(frame -> shortName(frame.getClassName() + "." + frame.getMethodName()))
                .orElseGet(() -> "thread " + Thread.currentThread().getName());
    }

    static String shortName(String qualifiedMethod) {
        int method = qualifiedMethod.lastIndexOf('.');
        int type = qualifiedMethod.lastIndexOf('.', method - 1);
        return type < 0 ? qualifiedMethod : qualifiedMethod.substring(type + 1);
    }

    static boolean isApplicationCode(String className) {
        return !className.contains("$$")
                && !className.contains("$HibernateProxy")
                && FRAMEWORK_PACKAGES.stream().noneMatch(className::startsWith);
    }

    private static Optional<StackWalker.StackFrame> applicationFrame() {
        return WALKER.walk(frames -> frames.filter(frame -> isApplicationCode(frame.getClassName())).findFirst());
    }
}
