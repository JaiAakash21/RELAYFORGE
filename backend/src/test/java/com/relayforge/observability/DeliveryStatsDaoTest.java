package com.relayforge.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the histogram percentile estimate.
 *
 * <p>The dashboard labels these values as approximate, and these tests pin down
 * what "approximate" means: the estimate must land inside the bucket the true
 * percentile falls in, and must degrade sensibly at the open-ended top bucket.
 */
class DeliveryStatsDaoTest {

    /** Bucket upper bounds: 50, 100, 250, 500, 1000, 2500, 5000, then open-ended. */
    private static long[] buckets(long... counts) {
        long[] full = new long[8];
        System.arraycopy(counts, 0, full, 0, counts.length);
        return full;
    }

    @Test
    @DisplayName("an empty histogram yields zero rather than NaN")
    void emptyHistogram() {
        assertThat(DeliveryStatsDao.percentile(buckets(), 0, 0.95)).isZero();
    }

    @Test
    @DisplayName("all observations in one bucket land inside that bucket")
    void singleBucket() {
        // 100 observations, all <= 50ms.
        double p50 = DeliveryStatsDao.percentile(buckets(100), 50, 0.50);
        assertThat(p50).isBetween(0.0, 50.0);
    }

    @Test
    @DisplayName("the median falls in the bucket holding the middle observation")
    void medianInCorrectBucket() {
        // 10 at <=50, 10 at <=100, 10 at <=250. The 15th of 30 is in the
        // second bucket, so p50 must be between 50 and 100.
        double p50 = DeliveryStatsDao.percentile(buckets(10, 10, 10), 250, 0.50);
        assertThat(p50).isBetween(50.0, 100.0);
    }

    @Test
    @DisplayName("a high percentile lands in a high bucket")
    void p95InTail() {
        // 95 fast, 5 slow. p95 sits right at the boundary into the slow bucket.
        double p95 = DeliveryStatsDao.percentile(buckets(95, 0, 0, 0, 0, 0, 5), 5000, 0.95);
        assertThat(p95).isGreaterThanOrEqualTo(50.0);
    }

    @Test
    @DisplayName("percentiles are monotonic")
    void monotonic() {
        long[] counts = buckets(20, 20, 20, 20, 10, 5, 3, 2);
        double p50 = DeliveryStatsDao.percentile(counts, 9000, 0.50);
        double p95 = DeliveryStatsDao.percentile(counts, 9000, 0.95);
        double p99 = DeliveryStatsDao.percentile(counts, 9000, 0.99);
        assertThat(p50).isLessThanOrEqualTo(p95);
        assertThat(p95).isLessThanOrEqualTo(p99);
    }

    @Test
    @DisplayName("the open-ended top bucket is bounded by the observed maximum")
    void topBucketUsesObservedMax() {
        // The final bucket has no upper bound. Extrapolating would invent a
        // number; using the observed maximum at least bounds the estimate by
        // something that actually happened.
        double p99 = DeliveryStatsDao.percentile(buckets(0, 0, 0, 0, 0, 0, 0, 100), 12_000, 0.99);
        assertThat(p99).isLessThanOrEqualTo(12_000.0);
        assertThat(p99).isGreaterThan(5000.0);
    }

    @Test
    @DisplayName("a percentile never exceeds the observed maximum")
    void neverExceedsObservedMax() {
        long[] counts = buckets(1, 1, 1, 1, 1, 1, 1, 1);
        assertThat(DeliveryStatsDao.percentile(counts, 7000, 0.99))
                .isLessThanOrEqualTo(7000.0);
    }
}
