package com.relayforge.messaging;

/** Kafka topic names, in one place so producers and consumers cannot drift. */
public final class Topics {

    /**
     * Delivery requests. One message per delivery attempt cycle.
     *
     * <p>Partitioned by endpoint id so that all work for one destination is
     * handled by one consumer in order. That gives per-endpoint serialisation for
     * free, which is what stops a burst of fifty events for one struggling
     * receiver from being fired at it in parallel.
     */
    public static final String DELIVERY_REQUESTS = "relayforge.delivery.requests";

    /**
     * Messages that could not be processed at all — malformed, or referencing a
     * delivery that does not exist.
     *
     * <p>Distinct from the <em>delivery</em> dead-letter queue, which is a
     * database view over exhausted deliveries. This topic is for broken
     * <em>messages</em>: a poison record that would otherwise block its partition
     * forever. Conflating the two would hide infrastructure bugs among ordinary
     * receiver failures.
     */
    public static final String DELIVERY_DLT = "relayforge.delivery.requests.dlt";

    private Topics() {}
}
