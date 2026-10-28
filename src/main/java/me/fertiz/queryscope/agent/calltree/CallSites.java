package me.fertiz.queryscope.agent.calltree;

import me.fertiz.queryscope.agent.QueryScopeAgent;

import java.util.List;
import java.util.Optional;

public final class CallSites {

    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final List<String> FRAMEWORK_PACKAGES = List.of(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.",
            "org.springframework.", "org.hibernate.", "org.apache.", "org.eclipse.", "org.jboss.",
            "com.zaxxer.", "org.h2.", "org.postgresql.", "com.mysql.", "org.mariadb.", "oracle.",
            "com.microsoft.sqlserver.", "com.fasterxml.", "tools.jackson.", "io.micrometer.",
            "org.flywaydb.", "liquibase.", "org.jooq.", "io.netty.", "reactor.", "kotlin.", "kotlinx.", "scala.",
            "groovy.", "org.codehaus.groovy.", "org.slf4j.", "ch.qos.logback.", "org.aspectj.",
            "org.junit.", "org.gradle.", "worker.org.gradle.", "com.intellij.", QueryScopeAgent.PACKAGE);

    private CallSites() {
    }

    public static String callSite() {
        return applicationFrame()
                .map(frame -> frame.getClassName() + "." + frame.getMethodName()
                        + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")")
                .orElse(null);
    }

    public static String entryName() {
        return applicationFrame()
                .map(frame -> shortName(frame.getClassName() + "." + frame.getMethodName()))
                .orElseGet(() -> "thread " + Thread.currentThread().getName());
    }

    public static String shortName(String qualifiedMethod) {
        int methodDot = qualifiedMethod.lastIndexOf('.');
        int typeDot = qualifiedMethod.lastIndexOf('.', methodDot - 1);
        return qualifiedMethod.substring(typeDot + 1);
    }

    static boolean isGenerated(String className) {
        return className.contains("$$") || className.contains("$HibernateProxy");
    }

    static boolean isApplicationCode(String className) {
        if (isGenerated(className)) {
            return false;
        }
        for (String framework : FRAMEWORK_PACKAGES) {
            if (className.startsWith(framework)) {
                return false;
            }
        }
        return true;
    }

    private static Optional<StackWalker.StackFrame> applicationFrame() {
        return WALKER.walk(frames -> frames.filter(frame -> isApplicationCode(frame.getClassName())).findFirst());
    }
}
