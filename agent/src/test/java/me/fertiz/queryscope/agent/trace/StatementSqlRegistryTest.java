package me.fertiz.queryscope.agent.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StatementSqlRegistryTest {

    private final StatementSqlRegistry registry = new StatementSqlRegistry();

    @Test
    void looksStatementsUpByIdentityNotEquality() {
        Object first = new EqualToEverything();
        Object second = new EqualToEverything();

        registry.remember(first, "select 1");
        registry.remember(second, "select 2");

        assertEquals("select 1", registry.sqlOf(first));
        assertEquals("select 2", registry.sqlOf(second));
        assertNull(registry.sqlOf(new EqualToEverything()));
    }

    @Test
    void forgetsStatementsOnceTheyAreGarbageCollected() throws InterruptedException {
        registry.remember(new Object(), "select 1");

        for (int attempt = 0; attempt < 50 && registry.size() > 0; attempt++) {
            System.gc();
            Thread.sleep(20);
        }

        assertEquals(0, registry.size());
    }

    private static final class EqualToEverything {

        @Override
        public boolean equals(Object other) {
            return true;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
