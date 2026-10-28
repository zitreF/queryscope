package me.fertiz.queryscope.agent.calltree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AppPackages {

    private static final Pattern JAR_END = Pattern.compile("\\.jar(?=\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Attributes.Name START_CLASS = new Attributes.Name("Start-Class");

    private final List<String> packages;

    private AppPackages(List<String> packages) {
        this.packages = List.copyOf(packages);
    }

    public static AppPackages of(List<String> packages) {
        return new AppPackages(packages);
    }

    public static AppPackages detect() {
        return new AppPackages(mainClass(System.getProperty("jdk.module.main.class"), System.getProperty("sun.java.command"))
                .flatMap(AppPackages::packageOf)
                .map(List::of)
                .orElse(List.of()));
    }

    public List<String> names() {
        return packages;
    }

    public boolean isEmpty() {
        return packages.isEmpty();
    }

    public boolean covers(String typeName) {
        return CallSites.isApplicationCode(typeName)
                && packages.stream().anyMatch(name -> typeName.startsWith(name + "."));
    }

    static Optional<String> mainClass(String moduleMainClass, String command) {
        if (moduleMainClass != null && !moduleMainClass.isBlank()) {
            return Optional.of(moduleMainClass.trim());
        }
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        Optional<Path> jar = jarOf(command.trim());
        if (jar.isPresent()) {
            return manifestMainClass(jar.get());
        }
        return Optional.of(command.trim().split("\\s+", 2)[0]);
    }

    static Optional<String> packageOf(String className) {
        int end = className.lastIndexOf('.');
        if (end < 0 || !CallSites.isApplicationCode(className)) {
            return Optional.empty();
        }
        String name = className.substring(0, end);
        return name.indexOf('.') < 0 ? Optional.empty() : Optional.of(name);
    }

    private static Optional<Path> jarOf(String command) {
        Matcher end = JAR_END.matcher(command);
        while (end.find()) {
            try {
                Path jar = Path.of(command.substring(0, end.end()));
                if (Files.isRegularFile(jar)) {
                    return Optional.of(jar);
                }
            } catch (InvalidPathException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<String> manifestMainClass(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            if (manifest == null) {
                return Optional.empty();
            }
            Attributes attributes = manifest.getMainAttributes();
            return Optional.ofNullable(attributes.getValue(START_CLASS))
                    .or(() -> Optional.ofNullable(attributes.getValue(Attributes.Name.MAIN_CLASS)))
                    .map(String::trim);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
