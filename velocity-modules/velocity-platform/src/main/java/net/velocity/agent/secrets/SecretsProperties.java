package net.velocity.agent.secrets;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.secrets")
public record SecretsProperties(
        boolean enabled,
        String key,
        String keyId,
        String previousKeys,
        int maxRecords,
        int maxReceipts) {

    public SecretsProperties {
        if (maxRecords < 1 || maxReceipts < 16) {
            throw new IllegalArgumentException("Invalid secrets capacity");
        }
    }

    @Override
    public String toString() {
        return "SecretsProperties[enabled=" + enabled
                + ", key=[REDACTED], keyId=[REDACTED], previousKeys=[REDACTED], maxRecords="
                + maxRecords + ", maxReceipts=" + maxReceipts + "]";
    }
}
