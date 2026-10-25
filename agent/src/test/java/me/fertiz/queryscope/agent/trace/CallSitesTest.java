package me.fertiz.queryscope.agent.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallSitesTest {

    @Test
    void treatsUserPackagesAsApplicationCode() {
        assertTrue(CallSites.isApplicationCode("com.acme.shop.OrderService"));
        assertTrue(CallSites.isApplicationCode("me.fertiz.queryscope.demo.customer.CustomerSummary"));
    }

    @Test
    void skipsFrameworksDriversAndGeneratedProxies() {
        assertFalse(CallSites.isApplicationCode("org.hibernate.collection.spi.PersistentBag"));
        assertFalse(CallSites.isApplicationCode("com.zaxxer.hikari.pool.HikariProxyPreparedStatement"));
        assertFalse(CallSites.isApplicationCode("com.acme.shop.OrderService$$SpringCGLIB$$0"));
        assertFalse(CallSites.isApplicationCode("com.acme.shop.Customer$HibernateProxy$x1y2"));
        assertFalse(CallSites.isApplicationCode("me.fertiz.queryscope.agent.trace.RequestTracer"));
    }
}
