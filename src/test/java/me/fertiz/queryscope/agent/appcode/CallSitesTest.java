package me.fertiz.queryscope.agent.appcode;

import me.fertiz.queryscope.agent.QueryScopeAgent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallSitesTest {

    @Test
    void knowsThePackageOfTheAgent() {
        assertEquals(QueryScopeAgent.class.getPackageName() + ".", CallSites.AGENT_PACKAGE_PREFIX);
        assertFalse(CallSites.isApplicationCode(QueryScopeAgent.class.getName()));
    }

    @Test
    void treatsGivenPackagesAsApplicationCodeEvenInsideFrameworkNamespaces() {
        String controller = "org.springframework.samples.petclinic.owner.OwnerController";
        assertFalse(CallSites.isApplicationCode(controller));
        try {
            CallSites.treatAsApplicationCode(List.of("org.springframework.samples.petclinic", "me.fertiz"));

            assertTrue(CallSites.isApplicationCode(controller));
            assertFalse(CallSites.isApplicationCode("org.springframework.web.servlet.DispatcherServlet"));
            assertFalse(CallSites.isApplicationCode(QueryScopeAgent.class.getName()));
        } finally {
            CallSites.treatAsApplicationCode(List.of());
        }
    }

    @Test
    void treatsUserPackagesAsApplicationCode() {
        assertTrue(CallSites.isApplicationCode("com.acme.shop.OrderService"));
        assertTrue(CallSites.isApplicationCode("me.fertiz.queryscope.demo.customer.CustomerSummary"));
    }

    @Test
    void shortensQualifiedMethodNamesToClassAndMethod() {
        assertEquals("ImportJob.run", CallSites.classAndMethod("com.acme.jobs.ImportJob.run"));
        assertEquals("Digest$Weekly.send", CallSites.classAndMethod("com.acme.Digest$Weekly.send"));
        assertEquals("Main.run", CallSites.classAndMethod("Main.run"));
    }

    @Test
    void skipsFrameworksDriversAndGeneratedProxies() {
        assertFalse(CallSites.isApplicationCode("org.hibernate.collection.spi.PersistentBag"));
        assertFalse(CallSites.isApplicationCode("com.zaxxer.hikari.pool.HikariProxyPreparedStatement"));
        assertFalse(CallSites.isApplicationCode("com.acme.shop.OrderService$$SpringCGLIB$$0"));
        assertFalse(CallSites.isApplicationCode("com.acme.shop.Customer$HibernateProxy$x1y2"));
        assertFalse(CallSites.isApplicationCode("me.fertiz.queryscope.agent.trace.UnitTracer"));
        assertFalse(CallSites.isApplicationCode("org.flywaydb.core.internal.jdbc.JdbcTemplate"));
    }
}
