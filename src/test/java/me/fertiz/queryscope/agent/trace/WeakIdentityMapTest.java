package me.fertiz.queryscope.agent.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeakIdentityMapTest {

    private final WeakIdentityMap<String> map = new WeakIdentityMap<>();

    @Test
    void looksKeysUpByIdentityNotEquality() {
        Object first = new EqualToEverything();
        Object second = new EqualToEverything();

        map.put(first, "select 1");
        map.put(second, "select 2");

        assertEquals("select 1", map.get(first));
        assertEquals("select 2", map.get(second));
        assertNull(map.get(new EqualToEverything()));
    }

    @Test
    void forgetsKeysOnceTheyAreGarbageCollected() throws InterruptedException {
        map.put(new Object(), "select 1");

        for (int attempt = 0; attempt < 50 && map.size() > 0; attempt++) {
            System.gc();
            Thread.sleep(20);
        }

        assertEquals(0, map.size());
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
