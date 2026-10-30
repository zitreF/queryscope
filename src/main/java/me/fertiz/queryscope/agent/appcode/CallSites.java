package me.fertiz.queryscope.agent.appcode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CallSites {

    public static final String AGENT_PACKAGE_PREFIX = "me.fertiz.queryscope.agent.";
    public static final String THREAD_NAME_PREFIX = "queryscope-";

    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final List<String> FRAMEWORK_PACKAGES = List.of(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.",
            "org.springframework.", "org.hibernate.", "org.apache.", "org.eclipse.", "org.jboss.",
            "com.zaxxer.", "org.h2.", "org.postgresql.", "com.mysql.", "org.mariadb.", "oracle.",
            "com.microsoft.sqlserver.", "com.fasterxml.", "tools.jackson.", "io.micrometer.",
            "org.flywaydb.", "liquibase.", "org.jooq.", "io.netty.", "reactor.", "kotlin.", "kotlinx.", "scala.",
            "groovy.", "org.codehaus.groovy.", "org.slf4j.", "ch.qos.logback.", "org.aspectj.",
            "org.junit.", "org.gradle.", "worker.org.gradle.", "com.intellij.", AGENT_PACKAGE_PREFIX);

    private static volatile List<String> givenPackagePrefixes = List.of();

    private CallSites() {
    }

    public static void treatAsApplicationCode(List<String> packages) {
        List<String> prefixes = new ArrayList<>();
        for (String name : packages) {
            prefixes.add(name + ".");
        }
        givenPackagePrefixes = List.copyOf(prefixes);
    }

    public static String currentCallSite() {
        return firstApplicationFrame()
                .map(frame -> frame.getClassName() + "." + frame.getMethodName()
                        + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")")
                .orElse(null);
    }

    public static String nearestApplicationMethodOrThread() {
        return firstApplicationFrame()
                .map(frame -> classAndMethod(frame.getClassName() + "." + frame.getMethodName()))
                .orElseGet(() -> "thread " + Thread.currentThread().getName());
    }

    public static String classAndMethod(String qualifiedMethod) {
        int methodDot = qualifiedMethod.lastIndexOf('.');
        int typeDot = qualifiedMethod.lastIndexOf('.', methodDot - 1);
        return qualifiedMethod.substring(typeDot + 1);
    }

    public static boolean isQueryScopeThread(String threadName) {
        return threadName != null && threadName.startsWith(THREAD_NAME_PREFIX);
    }

    static boolean isGenerated(String className) {
        return className.contains("$$") || className.contains("$HibernateProxy");
    }

    static boolean isApplicationCode(String className) {
        if (isGenerated(className) || className.startsWith(AGENT_PACKAGE_PREFIX)) {
            return false;
        }
        for (String prefix : givenPackagePrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        for (String framework : FRAMEWORK_PACKAGES) {
            if (className.startsWith(framework)) {
                return false;
            }
        }
        return true;
    }

    private static Optional<StackWalker.StackFrame> firstApplicationFrame() {
        return WALKER.walk(frames -> frames.filter(frame -> isApplicationCode(frame.getClassName())).findFirst());
    }
}
