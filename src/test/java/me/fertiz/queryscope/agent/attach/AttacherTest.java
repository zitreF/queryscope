package me.fertiz.queryscope.agent.attach;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttacherTest {

    @Test
    void namesProcessesAfterTheirJarOrMainClass() {
        assertEquals("matmaniak.jar", Attacher.name("/opt/apps/matmaniak.jar --server.port=8090"));
        assertEquals("ShopApplication", Attacher.name("com.acme.shop.ShopApplication"));
        assertEquals("Java process", Attacher.name(""));
    }

    @Test
    void leavesQueryScopeItselfOutOfTheList() {
        assertTrue(Attacher.isQueryScope("build/libs/queryscope.jar -cp app.jar com.acme.Main"));
        assertTrue(Attacher.isQueryScope("me.fertiz.queryscope.agent.launch.QueryScopeLauncher --port=9000"));
        assertFalse(Attacher.isQueryScope("com.acme.shop.ShopApplication"));
    }
}
