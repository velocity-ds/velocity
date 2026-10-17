package net.velocity.agent.config;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;

import net.velocity.ApplicationTime;
import net.velocity.agent.*;
import net.velocity.agent.accounts.AccountConfiguration;
import net.velocity.agent.accounts.AccountRepository;
import net.velocity.agent.delegation.ChildContextHooks;
import net.velocity.agent.delegation.ChildReferenceValidator;
import net.velocity.agent.delegation.DelegationProfileRegistry;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.persistence.MongoExecutionRepository;
import net.velocity.agent.spi.AuthorizationPolicyDecorator;
import net.velocity.agent.spi.ContextContributor;
import net.velocity.agent.spi.DelegationProfile;
import net.velocity.agent.spi.GatewayCustomizer;
import net.velocity.agent.spi.ToolGuidanceContributor;
import net.velocity.agent.spi.ToolProvider;
import net.velocity.agent.tools.DelegateWorkTool;
import net.velocity.agent.tools.TaskEvidenceTool;
import net.velocity.inference.InferenceProfileRegistry;
import net.velocity.inference.InferenceService;
import net.velocity.inference.SystemPrompts;

/**
 * Kernel wiring: the execution repository, the tool registry, the authorization policy and the gateway. It
 * references no platform or feature class. Platform capabilities (knowledge, scheduling, monitoring, learning,
 * research and the rest) and features contribute through the {@code net.velocity.agent.spi} types, which
 * this configuration collects in Spring order; see docs/agent-gateway.md (wiring contributions).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AgentProperties.class, DelegationProperties.class,
        AgentConcurrencyProperties.class, ApprovalProperties.class, LiveOutputProperties.class,
        UploadProperties.class, LocalAccountProperties.class})
@Import(AccountConfiguration.class)
public class AgentConfiguration {

    @Bean
    Object validateOperatorBoundary(Environment environment) {
        String forwarded = environment.getProperty("server.forward-headers-strategy", "none");
        if (!"none".equalsIgnoreCase(forwarded)
                || environment.getProperty("server.tomcat.remoteip.remote-ip-header") != null) {
            throw new IllegalArgumentException(
                    "The local agent operator API requires unmodified peer addresses; forwarding support must be disabled");
        }
        return new Object();
    }

    /** The application clock in {@link ApplicationTime#zone()}; time-dependent beans take it by injection. */
    @Bean
    @ConditionalOnMissingBean
    Clock applicationClock() {
        return Clock.system(ApplicationTime.zone());
    }

    /**
     * The delegation profiles contributed by features, in Spring order. Fails context startup on a duplicate or
     * invalid profile; see {@link DelegationProfileRegistry}.
     */
    @Bean
    @ConditionalOnMissingBean
    DelegationProfileRegistry delegationProfileRegistry(ObjectProvider<DelegationProfile> profiles) {
        return new DelegationProfileRegistry(profiles.orderedStream().toList());
    }

    /** Process-local fan-out of execution changes for live console views. */
    @Bean
    @ConditionalOnMissingBean
    ExecutionEvents executionEvents() {
        return new ExecutionEvents();
    }

    @Bean
    @ConditionalOnMissingBean
    ExecutionRepository executionRepository(
            MongoTemplate mongo,
            DelegationProfileRegistry delegationProfiles,
            ExecutionEvents events) {
        return new MongoExecutionRepository(mongo, delegationProfiles, events::changed);
    }

    /** Trusted registration metadata decides which calls wait for the requester; see docs/agent-gateway.md. */
    @Bean
    @ConditionalOnMissingBean
    ToolApprovalPolicy toolApprovalPolicy(ApprovalProperties properties) {
        return ToolApprovalPolicy.of(properties);
    }

    /** Core guidance about held calls, present while the policy is enabled or a registered tool confirms. */
    @Bean
    @Order(10)
    ContextContributor approvalGuidance(ToolApprovalPolicy policy, ToolRegistry tools) {
        return ToolGuidanceContributor
                .of(policy.guidanceNeeded(tools) ? SystemPrompts.TOOL_APPROVAL_INSTRUCTIONS : null);
    }

    @Bean
    @ConditionalOnMissingBean
    ModelClient agentModelClient(InferenceService inference) {
        return new InferenceModelClient(inference);
    }

    /**
     * Kernel tools first ({@code ask_user}, {@code datetime_now}, {@code task_evidence_read}), then every
     * {@link ToolProvider} in Spring order. The provider order values fix the registration order; see
     * docs/agent-gateway.md.
     */
    @Bean
    @ConditionalOnMissingBean
    ToolRegistry toolRegistry(
            Clock clock,
            ExecutionRepository executions,
            ObjectProvider<ToolProvider> providers) {
        ToolRegistry registry = ToolRegistry.standard(clock);
        TaskEvidenceTool.register(registry, executions);
        providers.orderedStream().forEach(provider -> provider.register(registry));
        return registry;
    }

    @Bean
    @Order(900)
    ToolProvider delegateToolProvider(
            DelegationProperties delegation,
            DelegationProfileRegistry delegationProfiles) {
        return registry -> {
            if (delegation.enabled() && !delegationProfiles.isEmpty()) {
                registry.register(DelegateWorkTool.definition(delegation, delegationProfiles));
            }
        };
    }

    /** Strict policy first, then each {@link AuthorizationPolicyDecorator} in Spring order (first is innermost). */
    @Bean
    @ConditionalOnMissingBean
    AuthorizationPolicy authorizationPolicy(ObjectProvider<AuthorizationPolicyDecorator> decorators) {
        AuthorizationPolicy policy = AuthorizationPolicy.strict();
        for (AuthorizationPolicyDecorator decorator : decorators.orderedStream().toList()) {
            policy = decorator.decorate(policy);
        }
        return policy;
    }

    @Bean
    @ConditionalOnMissingBean
    ContextHooks contextHooks() {
        return new ContextHooks() {};
    }

    @Bean
    @ConditionalOnMissingBean
    ChildContextHooks childContextHooks() {
        return ChildContextHooks.empty();
    }

    @Bean
    SchedulerIngressGateway schedulerIngressGateway(AgentGateway gateway, ContextHooks hooks) {
        return new SchedulerIngressGateway(gateway, hooks);
    }

    /**
     * The gateway takes only kernel collaborators. The attachment store and child reference validator are the
     * platform research service when it is present; without it uploads and delegated references are refused.
     * Platform capabilities install their verifiers, admission context and tool-dependent switches through
     * {@link GatewayCustomizer} beans, which run here before the init method ({@code recover}).
     */
    @Bean(initMethod = "recover", destroyMethod = "close")
    AgentGateway agentGateway(
            ExecutionRepository repository,
            ModelClient model,
            ToolRegistry tools,
            AuthorizationPolicy authorization,
            ContextHooks hooks,
            AgentProperties properties,
            DelegationProperties delegation,
            ChildContextHooks childHooks,
            InferenceProfileRegistry profiles,
            DelegationProfileRegistry delegationProfiles,
            AgentConcurrencyProperties concurrency,
            ToolApprovalPolicy approvalPolicy,
            ExecutionEvents events,
            LiveOutputProperties liveOutput,
            ObjectProvider<AttachmentStore> attachments,
            ObjectProvider<ChildReferenceValidator> references,
            @Lazy AccountRepository accounts,
            ObjectProvider<ContextContributor> contextContributors,
            ObjectProvider<GatewayCustomizer> customizers) {
        // Contributor order is part of the contract (Spring order of the contributor beans); see
        // ComposedContextHooks and docs/agent-gateway.md.
        ContextHooks effectiveHooks = new ComposedContextHooks(
                hooks,
                contextContributors.orderedStream().toList());
        var gateway = new AgentGateway(
                repository,
                model,
                tools,
                authorization,
                effectiveHooks,
                properties.limits().executionLimits(),
                properties.limits().taskTokenCeiling(),
                properties.automaticResumption(),
                delegation,
                childHooks,
                references.getIfAvailable(ChildReferenceValidator::none),
                attachments.getIfAvailable(AttachmentStore::none),
                profiles,
                delegationProfiles,
                concurrency);
        gateway.setAccountValidator(accounts::verify);
        gateway.setApprovalPolicy(approvalPolicy);
        gateway.setLiveOutput(events, liveOutput.modelStreaming());
        // Runs before the init method (recover), in Spring order; see GatewayCustomizer and docs/agent-gateway.md.
        customizers.orderedStream().forEach(customizer -> customizer.customize(gateway));
        return gateway;
    }
}
