package me.fertiz.queryscope.agent.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPackagesTest {

    @TempDir
    Path folder;

    @Test
    void takesTheMainClassFromTheCommand() {
        assertEquals(Optional.of("com.acme.shop.ShopApplication"),
                AppPackages.mainClass(null, "com.acme.shop.ShopApplication --server.port=8081"));
        assertEquals(Optional.of("com.acme.cli.Main"), AppPackages.mainClass("com.acme.cli.Main", "ignored"));
        assertEquals(Optional.empty(), AppPackages.mainClass(null, " "));
    }

    @Test
    void readsTheStartClassOfASpringBootJarEvenWithSpacesInItsPath() throws IOException {
        Path jar = jar(folder.resolve("my apps").resolve("shop.jar"),
                "org.springframework.boot.loader.launch.JarLauncher", "com.acme.shop.ShopApplication");

        assertEquals(Optional.of("com.acme.shop.ShopApplication"), AppPackages.mainClass(null, jar + " --debug"));
    }

    @Test
    void fallsBackToTheMainClassOfAPlainJar() throws IOException {
        Path jar = jar(folder.resolve("tool.jar"), "com.acme.tool.Main", null);

        assertEquals(Optional.of("com.acme.tool.Main"), AppPackages.mainClass(null, jar.toString()));
    }

    @Test
    void usesThePackageOfTheMainClassUnlessItIsAFrameworkOrTooShort() {
        assertEquals(Optional.of("com.acme.shop"), AppPackages.packageOf("com.acme.shop.ShopApplication"));
        assertEquals(Optional.empty(), AppPackages.packageOf("org.springframework.boot.loader.launch.JarLauncher"));
        assertEquals(Optional.empty(), AppPackages.packageOf("worker.org.gradle.process.internal.worker.GradleWorkerMain"));
        assertEquals(Optional.empty(), AppPackages.packageOf("app.Main"));
        assertEquals(Optional.empty(), AppPackages.packageOf("Main"));
    }

    @Test
    void coversApplicationClassesButNotGeneratedProxies() {
        AppPackages packages = AppPackages.of(List.of("com.acme.shop"));

        assertTrue(packages.covers("com.acme.shop.OrderService"));
        assertTrue(packages.covers("com.acme.shop.order.Order$Line"));
        assertFalse(packages.covers("com.acme.shopping.Cart"));
        assertFalse(packages.covers("com.acme.shop.OrderService$$SpringCGLIB$$0"));
        assertFalse(packages.covers("com.acme.shop.Customer$HibernateProxy$x1y2"));
    }

    private static Path jar(Path path, String mainClass, String startClass) throws IOException {
        Files.createDirectories(path.getParent());
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
        if (startClass != null) {
            attributes.put(new Attributes.Name("Start-Class"), startClass);
        }
        new JarOutputStream(Files.newOutputStream(path), manifest).close();
        return path;
    }
}
