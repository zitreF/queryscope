package me.fertiz.queryscope.agent.hub;

import me.fertiz.queryscope.agent.cli.QueryScopeLauncher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProcessesTest {

    @Test
    void namesProcessesAfterTheirJarOrMainClass() {
        assertEquals("matmaniak.jar", LocalProcesses.displayName("/opt/apps/matmaniak.jar --server.port=8090"));
        assertEquals("ShopApplication", LocalProcesses.displayName("com.acme.shop.ShopApplication"));
        assertEquals("Java process", LocalProcesses.displayName(""));
    }

    @Test
    void keepsSpacesInTheJarPath() {
        assertEquals("shop.jar", LocalProcesses.displayName("C:\\Program Files\\Acme\\shop.jar --debug"));
        assertTrue(LocalProcesses.isQueryScope("C:\\My Tools\\queryscope.jar --port=9000"));
    }

    @Test
    void leavesQueryScopeItselfOutOfTheList() {
        assertTrue(LocalProcesses.isQueryScope("build/libs/queryscope.jar -cp app.jar com.acme.Main"));
        assertTrue(LocalProcesses.isQueryScope(QueryScopeLauncher.class.getName() + " --port=9000"));
        assertFalse(LocalProcesses.isQueryScope("com.acme.shop.ShopApplication"));
    }
}
