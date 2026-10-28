package me.fertiz.queryscope.agent.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProcessesTest {

    @Test
    void namesProcessesAfterTheirJarOrMainClass() {
        assertEquals("matmaniak.jar", LocalProcesses.name("/opt/apps/matmaniak.jar --server.port=8090"));
        assertEquals("ShopApplication", LocalProcesses.name("com.acme.shop.ShopApplication"));
        assertEquals("Java process", LocalProcesses.name(""));
    }

    @Test
    void keepsSpacesInTheJarPath() {
        assertEquals("shop.jar", LocalProcesses.name("C:\\Program Files\\Acme\\shop.jar --debug"));
        assertTrue(LocalProcesses.isQueryScope("C:\\My Tools\\queryscope.jar --port=9000"));
    }

    @Test
    void leavesQueryScopeItselfOutOfTheList() {
        assertTrue(LocalProcesses.isQueryScope("build/libs/queryscope.jar -cp app.jar com.acme.Main"));
        assertTrue(LocalProcesses.isQueryScope("me.fertiz.queryscope.agent.launch.QueryScopeLauncher --port=9000"));
        assertFalse(LocalProcesses.isQueryScope("com.acme.shop.ShopApplication"));
    }
}
