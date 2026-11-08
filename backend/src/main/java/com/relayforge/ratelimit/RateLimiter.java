package com.relayforge.ratelimit;

import com.relayforge.common.RateLimitedException;
import com.relayforge.config.RelayForgeProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Distributed token-bucket rate limiting on Redis.
 *
 * <h2>Why a token bucket, and why in Lua</h2>
 *
 * A fixed window ({@code INCR} a per-second key) allows twice the intended rate
 * across a window boundary — 100 requests at 0.999s and 100 more at 1.001s. A
 * token bucket has no boundary to straddle, and its burst capacity is an
 * explicit parameter rather than an artefact of the window size.
 *
 * <p>The refill-and-consume sequence must be atomic. Implementing it as
 * {@code GET}, compute, {@code SET} across several API instances is a lost-update
 * race that silently lets the limit be exceeded under exactly the concurrency the
 * limiter exists to control. Redis runs a Lua script atomically against a single
 * key, so the whole decision happens as one indivisible operation with one round
 * trip.
 *
 * <h2>Two distinct limits, which are not the same thing</h2>
 *
 * <dl>
 *   <dt>Ingestion limits (tenant, API key)
 *   <dd>Protect <em>RelayForge</em> from a tenant flooding it. Breaching one
 *       returns 429 to the caller: backpressure applied at the edge, where the
 *       client can respond to it.
 *   <dt>Endpoint delivery limits
 *   <dd>Protect the <em>receiver</em> from RelayForge. Breaching one must never
 *       drop an event — the event has already been accepted and we promised to
 *       deliver it. Instead the delivery is rescheduled with a short delay, which
 *       is why {@link #tryConsumeEndpointDelivery} returns a boolean for the
 *       worker to act on rather than throwing.
 * </dl>
 *
 * <h2>Behaviour when Redis is down</h2>
 *
 * Configurable, defaulting to fail-open. Rate limiting is a protective
 * optimisation; the database constraints, payload caps and attempt budgets are
 * the correctness guarantees. Rejecting every event because the limiter is
 * unavailable would convert a Redis outage into a total ingestion outage, which
 * is a strictly worse failure. Each fail-open decision increments a counter so
 * the gap is visible on the dashboard rather than silent.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /**
     * Atomic refill-then-consume.
     *
     * <p>KEYS[1] bucket key. ARGV: capacity, refill tokens/sec, now (ms), requested.
     * Returns {@code {allowed, remaining, retryAfterMillis}}.
     *
     * <p>The TTL is set to the time needed to refill from empty to full, so idle
     * buckets evict themselves and Redis memory stays proportional to active
     * tenants rather than to every tenant that has ever sent an event.
     */
    private static final String TOKEN_BUCKET_SCRIPT =
            """
            local capacity   = tonumber(ARGV[1])
            local refillRate = tonumber(ARGV[2])
            local now        = tonumber(ARGV[3])
            local requested  = tonumber(ARGV[4])

            local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(bucket[1])
            local last   = tonumber(bucket[2])

            if tokens == nil then
              tokens = capacity
              last = now
            end

            -- Refill proportionally to elapsed time, never above capacity.
            local elapsed = math.max(0, now - last) / 1000.0
            tokens = math.min(capacity, tokens + (elapsed * refillRate))

            local allowed = 0
            local retryAfterMs = 0
            if tokens >= requested then
              tokens = tokens - requested
              allowed = 1
            else
              -- Time until enough tokens exist for this request.
              local deficit = requested - tokens
              retryAfterMs = math.ceil((deficit / refillRate) * 1000)
            end

            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
            local ttl = math.ceil(capacity / refillRate) + 2
            redis.call('EXPIRE', KEYS[1], ttl)

            return { allowed, math.floor(tokens), retryAfterMs }
            """;

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;
    private final RelayForgeProperties.RateLimits config;
    private final Counter failOpenCounter;
    private final Counter throttledCounter;

    public RateLimiter(
            StringRedisTemplate redis, RelayForgeProperties properties, MeterRegistry meters) {
        this.redis = redis;
        this.config = properties.rateLimits();

        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(TOKEN_BUCKET_SCRIPT);
        redisScript.setResultType(List.class);
        this.script = redisScript;

        this.failOpenCounter =
                Counter.builder("relayforge.ratelimit.unavailable")
                        .description(
                                "Rate-limit decisions that could not be made because Redis was"
                                        + " unreachable")
                        .register(meters);
        this.throttledCounter =
                Counter.builder("relayforge.ratelimit.throttled")
                        .description("Requests rejected by a rate limit")
                        .register(meters);
    }

    /** The outcome of one limiter consultation. */
    public record Decision(boolean allowed, long remaining, Duration retryAfter) {
        public static Decision allow(long remaining) {
            return new Decision(true, remaining, Duration.ZERO);
        }
    }

    /**
     * Applies the tenant and API-key ingestion limits.
     *
     * <p>Both are checked because they answer different questions: the tenant
     * limit is the contractual ceiling, and the per-key limit stops one runaway
     * integration from consuming the tenant's entire allowance and starving their
     * other services.
     *
     * @throws RateLimitedException when either limit is exhausted
     */
    public Decision checkEventIngestion(UUID tenantId, UUID apiKeyId) {
        Decision tenantDecision =
                consume(
                        "rl:tenant:events:" + tenantId,
                        config.tenantEventsBurst(),
                        config.tenantEventsPerSecond(),
                        1);
        if (!tenantDecision.allowed()) {
            throttledCounter.increment();
            throw new RateLimitedException(
                    "Tenant ingestion rate limit exceeded ("
                            + config.tenantEventsPerSecond()
                            + " events/second)",
                    Math.max(1, tenantDecision.retryAfter().toSeconds()));
        }

        if (apiKeyId != null) {
            Decision keyDecision =
                    consume(
                            "rl:apikey:events:" + apiKeyId,
                            config.apiKeyEventsBurst(),
                            config.apiKeyEventsPerSecond(),
                            1);
            if (!keyDecision.allowed()) {
                throttledCounter.increment();
                throw new RateLimitedException(
                        "API key ingestion rate limit exceeded ("
                                + config.apiKeyEventsPerSecond()
                                + " events/second)",
                        Math.max(1, keyDecision.retryAfter().toSeconds()));
            }
            return keyDecision;
        }
        return tenantDecision;
    }

    /**
     * Consults the per-endpoint delivery limit.
     *
     * <p>Returns rather than throws: the caller is a delivery worker holding an
     * accepted event, and the correct response to "too fast" is to wait, not to
     * discard.
     *
     * @param configuredPerSecond the endpoint's own limit, or {@code null} for the default
     */
    public Decision tryConsumeEndpointDelivery(UUID endpointId, Integer configuredPerSecond) {
        int perSecond =
                configuredPerSecond != null && configuredPerSecond > 0
                        ? configuredPerSecond
                        : config.endpointDeliveriesPerSecond();
        int burst = Math.max(perSecond, config.endpointDeliveriesBurst());
        return consume("rl:endpoint:delivery:" + endpointId, burst, perSecond, 1);
    }

    /**
     * Throttles authentication attempts per client.
     *
     * <p>Expressed per minute because login is a human action: a per-second limit
     * would be far too generous to be a meaningful brake on credential stuffing.
     */
    public void checkAuthAttempt(String clientKey) {
        double perSecond = config.authAttemptsPerMinute() / 60.0;
        Decision decision =
                consume(
                        "rl:auth:" + clientKey,
                        config.authAttemptsPerMinute(),
                        perSecond,
                        1);
        if (!decision.allowed()) {
            throttledCounter.increment();
            throw new RateLimitedException(
                    "Too many authentication attempts; try again shortly",
                    Math.max(1, decision.retryAfter().toSeconds()));
        }
    }

    /** Runs the script, translating a Redis outage into the configured posture. */
    @SuppressWarnings("unchecked")
    private Decision consume(String key, long capacity, double refillPerSecond, int requested) {
        try {
            List<Long> result =
                    (List<Long>)
                            redis.execute(
                                    script,
                                    List.of(key),
                                    Long.toString(capacity),
                                    Double.toString(refillPerSecond),
                                    Long.toString(System.currentTimeMillis()),
                                    Integer.toString(requested));
            if (result == null || result.size() < 3) {
                return failOpen(key, "unexpected script result");
            }
            boolean allowed = result.get(0) != null && result.get(0) == 1L;
            long remaining = result.get(1) == null ? 0L : result.get(1);
            long retryAfterMs = result.get(2) == null ? 0L : result.get(2);
            return new Decision(allowed, remaining, Duration.ofMillis(retryAfterMs));
        } catch (RuntimeException e) {
            return failOpen(key, e.getMessage());
        }
    }

    private Decision failOpen(String key, String reason) {
        failOpenCounter.increment();
        if (config.failOpen()) {
            log.warn(
                    "Rate limiter unavailable for key {} ({}); allowing the request because"
                            + " relayforge.rate-limits.fail-open is enabled",
                    key,
                    reason);
            return Decision.allow(-1);
        }
        log.error(
                "Rate limiter unavailable for key {} ({}); rejecting the request because"
                        + " relayforge.rate-limits.fail-open is disabled",
                key,
                reason);
        throw new RateLimitedException(
                "Rate limiting is temporarily unavailable; please retry", 5);
    }
}
