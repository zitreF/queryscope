package me.fertiz.queryscope.agent.methodtrace;

import java.util.concurrent.atomic.AtomicLongArray;

final class LatencyHistogram {

    private static final int SUB_BUCKET_BITS = 3;
    private static final int SUB_BUCKETS = 1 << SUB_BUCKET_BITS;
    private static final int BUCKETS = Long.SIZE * SUB_BUCKETS;

    private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);

    void record(long nanos) {
        counts.incrementAndGet(bucketOf(Math.max(1, nanos)));
    }

    long percentile(double fraction) {
        long total = 0;
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            total += counts.get(bucket);
        }
        if (total == 0) {
            return 0;
        }
        long rank = Math.max(1, (long) Math.ceil(fraction * total));
        long seen = 0;
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            seen += counts.get(bucket);
            if (seen >= rank) {
                return middleOf(bucket);
            }
        }
        return Long.MAX_VALUE;
    }

    static int bucketOf(long nanos) {
        int magnitude = Long.SIZE - 1 - Long.numberOfLeadingZeros(nanos);
        if (magnitude < SUB_BUCKET_BITS) {
            return (int) nanos;
        }
        int subBucket = (int) ((nanos >>> (magnitude - SUB_BUCKET_BITS)) & (SUB_BUCKETS - 1));
        return magnitude * SUB_BUCKETS + subBucket;
    }

    static long middleOf(int bucket) {
        int magnitude = bucket / SUB_BUCKETS;
        if (magnitude < SUB_BUCKET_BITS) {
            return bucket;
        }
        int shift = magnitude - SUB_BUCKET_BITS;
        long lowest = (long) (SUB_BUCKETS + bucket % SUB_BUCKETS) << shift;
        return lowest + (1L << shift) / 2;
    }
}
