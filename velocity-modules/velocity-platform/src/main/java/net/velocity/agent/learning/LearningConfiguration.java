package net.velocity.agent.learning;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;

import net.velocity.agent.AuthorizationPolicy;
import net.velocity.agent.InferenceModelClient;
import net.velocity.agent.PinnedInference;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.memory.MemoryProperties;
import net.velocity.agent.memory.MemoryService;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.scheduling.ScheduleDelivery;
import net.velocity.agent.skills.SkillService;
import net.velocity.agent.skills.SkillsProperties;
import net.velocity.agent.spi.GatewayCustomizer;
import net.velocity.agent.state.InferenceBinding;
import net.velocity.inference.InferenceProfileRegistry;
import net.velocity.inference.SystemPrompts;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LearningProperties.class)
public class LearningConfiguration {

    @Bean
    @Lazy
    LearningRepository learningRepository(MongoTemplate mongo) {
        return new MongoLearningRepository(mongo);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    LearningService learningService(
            @Lazy LearningRepository repository,
            ExecutionRepository executions,
            LearningProperties properties,
            InferenceProfileRegistry profiles,
            ToolRegistry tools,
            AuthorizationPolicy authorization,
            MemoryService memory,
            SkillService skills,
            ObjectProvider<ScheduleDelivery> delivery,
            Clock clock) {
        String reviewProfile = properties.reviewProfile();
        if (properties.enabled() && reviewProfile != null && !profiles.available(reviewProfile)) {
            throw new IllegalStateException(
                    "agent.learning.profile names an unconfigured inference profile: " + reviewProfile);
        }
        return new LearningService(repository, executions, properties, binding -> {
            if (reviewProfile != null) {
                // Reviews run on the dedicated review profile regardless of the task's pinned binding.
                var profile = profiles.find(reviewProfile)
                        .orElseThrow(() -> new IllegalStateException("review_inference_profile_unavailable"));
                return new LearningService.ReviewModel(
                        new InferenceModelClient(profile.service()),
                        InferenceBinding.of(profile));
            }
            var resolution = PinnedInference.resolve(binding, profiles);
            if (resolution.status() == PinnedInference.Status.incompatible) {
                throw new IllegalStateException("review_inference_profile_incompatible");
            }
            if (!resolution.available()) {
                throw new IllegalStateException("review_inference_profile_unavailable");
            }
            return new LearningService.ReviewModel(
                    new InferenceModelClient(resolution.profile().service()),
                    binding);
        },
                tools,
                authorization,
                memory,
                skills,
                delivery.getIfAvailable(ScheduleDelivery::unavailable),
                clock);
    }

    /** Learning reviews must settle and refuse new work on an execution before its content is deleted. */
    @Bean
    @Order(100)
    GatewayCustomizer learningDeletionBarrier(LearningService learning) {
        return gateway -> gateway.registerDeletionBarrier(learning::deleteExecution);
    }

    /**
     * Frozen admission context (saved facts and the skill index) and the foreground/delivery callbacks the
     * learning loop uses to decide when a review may run. Admission reads the knowledge once; replay and
     * steering never reload it.
     */
    @Bean
    @Order(800)
    GatewayCustomizer learningAdmission(
            LearningService learning,
            MemoryService memory,
            MemoryProperties memoryProperties,
            SkillService skills,
            SkillsProperties skillsProperties,
            ToolRegistry tools) {
        return gateway -> gateway.setAdmissionLearning(scope -> {
            String facts = tools.find("memory_update").isPresent() ? memory.snapshot(scope) : "";
            String index = tools.find("skill_load").isPresent() ? skills.admissionIndex(scope) : "";
            return (memoryProperties.enabled() || skillsProperties.enabled()
                    ? SystemPrompts.LEARNING_INSTRUCTIONS + "\n"
                    : "")
                    + facts + "\n" + index;
        },
                learning::foregroundStarted,
                learning::foregroundSettled,
                snapshot -> learning.acknowledge(
                        snapshot,
                        snapshot.deliveryTurn,
                        LearningService.responseDigest(snapshot)));
    }
}
