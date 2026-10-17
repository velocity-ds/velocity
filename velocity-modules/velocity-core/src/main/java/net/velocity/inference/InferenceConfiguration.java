package net.velocity.inference;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import net.velocity.inference.claude.ClaudeInferenceProvider;
import net.velocity.inference.claude.ClaudeProperties;
import net.velocity.inference.llamacpp.LlamaCppInferenceProvider;
import net.velocity.inference.llamacpp.LlamaCppProperties;
import net.velocity.inference.openai.OpenAIInferenceProvider;
import net.velocity.inference.openai.OpenAIProperties;
import net.velocity.usage.UsageLedger;

/** Owns provider selection, configuration binding, and SDK client lifecycles. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InferenceConcurrencyProperties.class)
public class InferenceConfiguration {

    @Bean
    InferenceRequestLimiter inferenceRequestLimiter(InferenceConcurrencyProperties properties) {
        return new InferenceRequestLimiter(properties);
    }

    @Bean
    Object validateInferenceSelection(Environment environment) {
        String selected = environment.getProperty("inference.provider", "");
        if (!selected.isEmpty() && !java.util.Set.of("claude", "openai", "llama-cpp").contains(selected)) {
            throw new IllegalArgumentException(
                    "Unsupported inference.provider: explicitly select claude, openai, or llama-cpp");
        }
        return new Object();
    }

    @Bean
    InferenceProfileRegistry inferenceProfileRegistry(
            InferenceService defaultService,
            Environment environment,
            ObjectProvider<PrimaryInferenceProperties> primarySettings,
            @Qualifier("cloudFallbackProfile") ObjectProvider<InferenceProfile> fallback) {
        List<InferenceProfile> profiles = new ArrayList<>();
        // A provider supplied outside the built-in selector remains valid for direct inference,
        // but has no durable profile: agent tasks need a pinned model identity and are unavailable.
        if (!environment.getProperty("inference.provider", "").isEmpty() && defaultService.available()) {
            PrimaryInferenceProperties primary = primarySettings.getObject();
            String providerId = defaultService.providerId();
            profiles.add(
                    new InferenceProfile(
                            InferenceProfileRegistry.DEFAULT,
                            providerId,
                            environment.getRequiredProperty("inference." + providerId + ".model"),
                            primary.contextCapacity(),
                            primary.inputAllowance(),
                            defaultService.outputTokenAllowance(),
                            primary.safetyMargin(),
                            defaultService));
        }
        fallback.ifAvailable(profiles::add);
        return new InferenceProfileRegistry(profiles);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "inference.cloud-fallback", name = "enabled", havingValue = "true")
    @EnableConfigurationProperties(CloudFallbackProperties.class)
    static class CloudFallbackConfiguration {

        @Bean
        Object validateCloudFallbackSelection(CloudFallbackProperties properties) {
            // Force validation even when no provider-specific configuration matches the selector.
            return new Object();
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnProperty(prefix = "inference.cloud-fallback", name = "enabled", havingValue = "true")
        @ConditionalOnProperty(prefix = "inference.cloud-fallback", name = "provider", havingValue = "openai")
        static class OpenAIFallbackConfiguration {

            @Bean(destroyMethod = "close")
            OpenAIClient cloudFallbackOpenAIClient(
                    CloudFallbackProperties properties,
                    Environment environment) {
                String apiKey = requiredApiKey(environment, "OPENAI_API_KEY");
                return OpenAIOkHttpClient.builder().fromEnv().apiKey(apiKey).build();
            }

            @Bean("cloudFallbackProfile")
            InferenceProfile cloudFallbackProfile(
                    @Qualifier("cloudFallbackOpenAIClient") OpenAIClient client,
                    CloudFallbackProperties properties,
                    ObjectProvider<UsageLedger> ledgers,
                    InferenceRequestLimiter limiter) {
                var provider = new OpenAIInferenceProvider(
                        client,
                        new OpenAIProperties(properties.model(), properties.outputReserve()));
                return fallbackProfile(
                        properties,
                        new InferenceService(provider, ledgers.getIfAvailable(), limiter));
            }
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnProperty(prefix = "inference.cloud-fallback", name = "enabled", havingValue = "true")
        @ConditionalOnProperty(prefix = "inference.cloud-fallback", name = "provider", havingValue = "claude")
        static class ClaudeFallbackConfiguration {

            @Bean(destroyMethod = "close")
            AnthropicClient cloudFallbackAnthropicClient(
                    CloudFallbackProperties properties,
                    Environment environment) {
                String apiKey = requiredApiKey(environment, "ANTHROPIC_API_KEY");
                return AnthropicOkHttpClient.builder().fromEnv().apiKey(apiKey).build();
            }

            @Bean("cloudFallbackProfile")
            InferenceProfile cloudFallbackProfile(
                    @Qualifier("cloudFallbackAnthropicClient") AnthropicClient client,
                    CloudFallbackProperties properties,
                    ObjectProvider<UsageLedger> ledgers,
                    InferenceRequestLimiter limiter) {
                var provider = new ClaudeInferenceProvider(
                        client,
                        new ClaudeProperties(properties.model(), properties.outputReserve()));
                return fallbackProfile(
                        properties,
                        new InferenceService(provider, ledgers.getIfAvailable(), limiter));
            }
        }

        private static InferenceProfile fallbackProfile(
                CloudFallbackProperties properties,
                InferenceService service) {
            // Keep fallback services/providers out of type-based default injection. Spring owns the SDK clients.
            return new InferenceProfile(
                    InferenceProfileRegistry.CLOUD_FALLBACK,
                    properties.provider(),
                    properties.model(),
                    properties.contextCapacity(),
                    properties.inputAllowance(),
                    properties.outputReserve(),
                    properties.safetyMargin(),
                    service);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "inference", name = "provider", havingValue = "claude")
    @EnableConfigurationProperties({ClaudeProperties.class, PrimaryInferenceProperties.class})
    static class ClaudeConfiguration {

        @Bean(destroyMethod = "close")
        AnthropicClient anthropicClient(
                ClaudeProperties properties,
                PrimaryInferenceProperties primary,
                Environment environment) {
            // Resolve validated settings before allocating SDK resources or reading credentials.
            String apiKey = requiredApiKey(environment, "ANTHROPIC_API_KEY");
            return AnthropicOkHttpClient.builder().fromEnv().apiKey(apiKey).build();
        }

        @Bean(name = {"defaultInferenceProvider", "claudeInferenceProvider"})
        ClaudeInferenceProvider claudeInferenceProvider(
                @Qualifier("anthropicClient") AnthropicClient client,
                ClaudeProperties properties) {
            return new ClaudeInferenceProvider(client, properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "inference", name = "provider", havingValue = "openai")
    @EnableConfigurationProperties({OpenAIProperties.class, PrimaryInferenceProperties.class})
    static class OpenAIConfiguration {

        @Bean(destroyMethod = "close")
        OpenAIClient openAIClient(
                OpenAIProperties properties,
                PrimaryInferenceProperties primary,
                Environment environment) {
            // Resolve validated settings before allocating SDK resources or reading credentials.
            String apiKey = requiredApiKey(environment, "OPENAI_API_KEY");
            return OpenAIOkHttpClient.builder().fromEnv().apiKey(apiKey).build();
        }

        @Bean(name = {"defaultInferenceProvider", "openAIInferenceProvider"})
        OpenAIInferenceProvider openAIInferenceProvider(
                @Qualifier("openAIClient") OpenAIClient client,
                OpenAIProperties properties) {
            return new OpenAIInferenceProvider(client, properties);
        }
    }

    private static String requiredApiKey(Environment environment, String name) {
        String apiKey = environment.getProperty(name);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Set " + name
                            + " in .env or the process environment for the selected inference provider");
        }
        return apiKey;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "inference", name = "provider", havingValue = "llama-cpp")
    @EnableConfigurationProperties({LlamaCppProperties.class, PrimaryInferenceProperties.class})
    static class LlamaCppConfiguration {

        @Bean(destroyMethod = "close")
        OpenAIClient llamaCppClient(LlamaCppProperties properties, PrimaryInferenceProperties primary) {
            String apiKey = properties.apiKey();
            return OpenAIOkHttpClient.builder()
                    .baseUrl(properties.baseUrl())
                    // llama-server defaults to closing idle connections after five seconds. Retire
                    // pooled sockets sooner, including while browser tools are running.
                    .maxIdleConnections(5)
                    .keepAliveDuration(Duration.ofSeconds(3))
                    // The SDK requires a key even when llama-server authentication is disabled.
                    .apiKey(apiKey == null || apiKey.isBlank() ? "llama-cpp-no-key" : apiKey)
                    .build();
        }

        @Bean(name = {"defaultInferenceProvider", "llamaCppInferenceProvider"})
        LlamaCppInferenceProvider llamaCppInferenceProvider(
                @Qualifier("llamaCppClient") OpenAIClient client,
                LlamaCppProperties properties) {
            return new LlamaCppInferenceProvider(client, properties);
        }
    }

}
