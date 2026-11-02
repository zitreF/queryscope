package me.fertiz.queryscope.agent.trace;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class WeakIdentityMap<V> {

    private final Map<Object, V> values = new ConcurrentHashMap<>();
    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();

    V put(Object key, V value) {
        removeCollected();
        return values.put(new IdentityKey(key, collected), value);
    }

    V get(Object key) {
        return values.get(new Probe(key));
    }

    V remove(Object key) {
        return values.remove(new Probe(key));
    }

    boolean isEmpty() {
        return values.isEmpty();
    }

    int size() {
        removeCollected();
        return values.size();
    }

    private void removeCollected() {
        Reference<?> key;
        while ((key = collected.poll()) != null) {
            values.remove(key);
        }
    }

    private record Probe(Object key) {

        @Override
        public int hashCode() {
            return System.identityHashCode(key);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey stored && stored.get() == key;
        }
    }

    private static final class IdentityKey extends WeakReference<Object> {

        private final int hash;

        private IdentityKey(Object key, ReferenceQueue<Object> queue) {
            super(key, queue);
            this.hash = System.identityHashCode(key);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IdentityKey key)) {
                return false;
            }
            Object referent = get();
            return referent != null && referent == key.get();
        }
    }
}
