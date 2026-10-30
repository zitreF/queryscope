package me.fertiz.queryscope.e2e;

import me.fertiz.queryscope.war.BookshelfServlet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import static me.fertiz.queryscope.e2e.DemoProcess.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "queryscope.tomcat", matches = ".+")
class TomcatEndToEndTest {

    private static final String WAR_PACKAGE = BookshelfServlet.class.getPackageName();

    @TempDir
    static Path catalinaBase;

    private static DemoProcess tomcat;
    private static int appPort;
    private static int panelPort;

    @BeforeAll
    static void startTomcatWithTheWar() throws Exception {
        Path catalinaHome = Path.of(System.getProperty("queryscope.tomcat"));
        appPort = DemoProcess.freePort();
        panelPort = DemoProcess.freePort();
        copyFolder(catalinaHome.resolve("conf"), catalinaBase.resolve("conf"));
        Path serverXml = catalinaBase.resolve("conf").resolve("server.xml");
        Files.writeString(serverXml, Files.readString(serverXml)
                .replace("port=\"8005\"", "port=\"-1\"")
                .replace("port=\"8080\"", "port=\"" + appPort + "\""));
        for (String folder : List.of("webapps", "logs", "temp", "work")) {
            Files.createDirectories(catalinaBase.resolve(folder));
        }
        Files.copy(Path.of(System.getProperty("queryscope.bookshelfWar")),
                catalinaBase.resolve("webapps").resolve("bookshelf.war"));
        tomcat = DemoProcess.start(List.of(
                        DemoProcess.JAVA,
                        "-javaagent:" + DemoProcess.AGENT_JAR + "=port=" + panelPort + ",package=" + WAR_PACKAGE,
                        "-Dcatalina.home=" + catalinaHome,
                        "-Dcatalina.base=" + catalinaBase,
                        "-Djava.io.tmpdir=" + catalinaBase.resolve("temp"),
                        "-cp", catalinaHome.resolve("bin").resolve("bootstrap.jar") + File.pathSeparator
                                + catalinaHome.resolve("bin").resolve("tomcat-juli.jar"),
                        "org.apache.catalina.startup.Bootstrap", "start"),
                Path.of("build", "e2e-tomcat.log"), appPort);
    }

    @AfterAll
    static void stopTomcat() throws InterruptedException {
        if (tomcat != null) {
            tomcat.close();
        }
    }

    @Test
    void findsTheNPlusOneOfAServletInAWar() throws Exception {
        assertEquals(200, get(appPort, "/bookshelf/books").statusCode());

        JsonNode report = QueryScopeEndToEndTest.reportFor(panelPort, "/bookshelf/books");
        assertEquals("GET", report.path("method").asString());
        assertEquals(6, report.path("queries").size());
        JsonNode nPlusOne = report.path("findings").get(0);
        assertEquals("nPlusOne", nPlusOne.path("type").asString(), report.toString());
        assertTrue(nPlusOne.path("callSite").asString().startsWith(BookshelfServlet.class.getName() + ".title("),
                nPlusOne.toString());
    }

    @Test
    void recordsTheCallTreeOfTheWarsOwnClasses() throws Exception {
        assertEquals(200, get(appPort, "/bookshelf/books").statusCode());

        JsonNode report = QueryScopeEndToEndTest.reportFor(panelPort, "/bookshelf/books");
        assertTrue(report.path("methodCount").asInt() >= 2, report.toString());
        assertTrue(tomcat.output().contains("QueryScope records the call tree of " + WAR_PACKAGE), tomcat.output());
    }

    private static void copyFolder(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
