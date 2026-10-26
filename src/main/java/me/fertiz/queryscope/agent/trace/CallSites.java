package me.fertiz.queryscope.agent.trace;

import java.util.List;

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
        return WALKER.walk(frames -> frames
                .filter(frame -> isApplicationCode(frame.getClassName()))
                .findFirst()
                .map(CallSites::describe)
                .orElse(null));
    }

    static boolean isApplicationCode(String className) {
        return !className.contains("$$")
                && !className.contains("$HibernateProxy")
                && FRAMEWORK_PACKAGES.stream().noneMatch(className::startsWith);
    }

    private static String describe(StackWalker.StackFrame frame) {
        return frame.getClassName() + "." + frame.getMethodName()
                + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")";
    }
}
