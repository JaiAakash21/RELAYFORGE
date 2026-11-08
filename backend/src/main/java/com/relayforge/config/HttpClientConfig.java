package com.relayforge.config;

import com.relayforge.security.PinnedDnsResolver;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The outbound HTTP client used for webhook delivery.
 *
 * <p>Every setting here exists to stop a misbehaving receiver from damaging the
 * platform. A webhook sender talks to thousands of endpoints it does not
 * control, so the client has to assume each one may be slow, hostile or broken.
 */
@Configuration
public class HttpClientConfig {

    /**
     * Shared pin registry. Package-visible as a bean so the delivery client can
     * register validated addresses and the HTTP client can read them.
     */
    @Bean
    public PinnedDnsResolver pinnedDnsResolver() {
        return new PinnedDnsResolver();
    }

    @Bean(destroyMethod = "close")
    public CloseableHttpClient webhookHttpClient(
            RelayForgeProperties properties, PinnedDnsResolver pinnedDnsResolver) {

        RelayForgeProperties.Delivery cfg = properties.delivery();

        ConnectionConfig connectionConfig =
                ConnectionConfig.custom()
                        .setConnectTimeout(
                                Timeout.ofMilliseconds(cfg.connectTimeout().toMillis()))
                        .setSocketTimeout(
                                Timeout.ofMilliseconds(cfg.responseTimeout().toMillis()))
                        // Revalidate a pooled connection that has been idle, rather
                        // than discovering it is dead by failing a real delivery.
                        .setValidateAfterInactivity(TimeValue.ofSeconds(5))
                        .build();

        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        // The whole point of this config class: the client resolves
                        // hostnames through the pin registry, never through system DNS.
                        .setDnsResolver(pinnedDnsResolver)
                        .setDefaultConnectionConfig(connectionConfig)
                        .setMaxConnTotal(cfg.maxPooledConnections())
                        // Bounded per route so one slow destination cannot starve
                        // every other tenant's deliveries of connections.
                        .setMaxConnPerRoute(cfg.maxConnectionsPerRoute())
                        .build();

        RequestConfig requestConfig =
                RequestConfig.custom()
                        .setConnectionRequestTimeout(
                                Timeout.ofMilliseconds(cfg.connectTimeout().toMillis()))
                        .setResponseTimeout(
                                Timeout.ofMilliseconds(cfg.responseTimeout().toMillis()))
                        // Redirects are NOT followed. A 30x is treated as a terminal
                        // failure: following one would let a receiver redirect our
                        // signed request — and whatever it carries — to an address
                        // that never passed the SSRF policy.
                        .setRedirectsEnabled(false)
                        .setCircularRedirectsAllowed(false)
                        .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .setUserAgent(cfg.userAgent())
                // No automatic retry at the HTTP layer. Retrying is a domain
                // decision made by the retry engine, which records every attempt
                // and applies backoff; a silent library-level retry would make the
                // attempt history a lie.
                .disableAutomaticRetries()
                .disableRedirectHandling()
                // No cookie state: there is no session to maintain with a receiver,
                // and a shared cookie store across tenants would be a leak.
                .disableCookieManagement()
                .evictIdleConnections(TimeValue.of(30, TimeUnit.SECONDS))
                .build();
    }
}
