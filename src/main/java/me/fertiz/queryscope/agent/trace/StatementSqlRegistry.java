package me.fertiz.queryscope.agent.trace;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class StatementSqlRegistry {

    private final Map<IdentityKey, String> sqlByStatement = new ConcurrentHashMap<>();
    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();

    void remember(Object statement, String sql) {
        removeCollected();
        sqlByStatement.put(new IdentityKey(statement, collected), sql);
    }

    String sqlOf(Object statement) {
        return sqlByStatement.get(new IdentityKey(statement, null));
    }

    int size() {
        removeCollected();
        return sqlByStatement.size();
    }

    private void removeCollected() {
        Reference<?> key;
        while ((key = collected.poll()) != null) {
            sqlByStatement.remove(key);
        }
    }

    private static final class IdentityKey extends WeakReference<Object> {

        private final int hash;

        private IdentityKey(Object statement, ReferenceQueue<Object> queue) {
            super(statement, queue);
            this.hash = System.identityHashCode(statement);
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
            Object statement = get();
            return statement != null && statement == key.get();
        }
    }
}
