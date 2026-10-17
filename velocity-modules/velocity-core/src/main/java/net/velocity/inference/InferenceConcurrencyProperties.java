package net.velocity.inference;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** A single installation-wide pool includes all configured provider profiles and auxiliary clients. */
@ConfigurationProperties("inference.concurrency")
public record InferenceConcurrencyProperties(
        int maxRequests,
        int queueCapacity,
        long maxWaitMillis) {

    public InferenceConcurrencyProperties {
        if (maxRequests < 1 || queueCapacity < 0 || maxWaitMillis < 1) {
            throw new IllegalArgumentException(
                    "Inference concurrency and queue wait must be positive; queue capacity must be nonnegative");
        }
    }

    public static InferenceConcurrencyProperties defaults() {
        return new InferenceConcurrencyProperties(4, 100, 30_000);
    }
}
