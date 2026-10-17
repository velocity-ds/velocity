package net.velocity.agent.codeexecution;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;

import net.velocity.agent.AuthorizationPolicy;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.accounts.AccountRepository;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.research.ResearchRepository;
import net.velocity.agent.skills.BundledSkill;
import net.velocity.agent.skills.BundledSkillSource;
import net.velocity.agent.spi.ContextContributor;
import net.velocity.agent.spi.GatewayCustomizer;
import net.velocity.agent.spi.OutputFileSource;
import net.velocity.agent.spi.SystemStatusContributor;
import net.velocity.agent.spi.ToolGuidanceContributor;
import net.velocity.agent.spi.ToolProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CodeExecutionProperties.class)
public class CodeExecutionConfiguration {

    /**
     * The PDF and Excel workflows describe the code execution tool, so this feature owns them. Always registered,
     * enabled or not, so the operator catalog does not change with the feature flag. Dependency-free and
     * {@code static} so the catalog can be built before any other bean.
     */
    @Bean
    @Order(100)
    static BundledSkillSource codeExecutionBundledSkills() {
        // Released versions are immutable: changing their content requires a new version and receipt identity.
        return () -> List.of(
                new BundledSkill(
                        "pdf",
                        1,
                        "Velocity PDF documents",
                        CodeExecutionPrompts.VELOCITY_PDF_SKILL_ROUTING,
                        CodeExecutionPrompts.VELOCITY_PDF_SKILL_CORE,
                        "PDF workflow example",
                        CodeExecutionPrompts.VELOCITY_PDF_SKILL_REFERENCE),
                new BundledSkill(
                        "xlsx",
                        1,
                        "Velocity Excel workbooks",
                        CodeExecutionPrompts.VELOCITY_XLSX_SKILL_ROUTING,
                        CodeExecutionPrompts.VELOCITY_XLSX_SKILL_CORE,
                        "Excel workflow example",
                        CodeExecutionPrompts.VELOCITY_XLSX_SKILL_REFERENCE));
    }

    /** The code execution section of the operator status document; {@code null} when the service is absent. */
    @Bean
    @Order(1400)
    SystemStatusContributor codeExecutionStatus(ObjectProvider<CodeExecutionService> codeExecution) {
        return SystemStatusContributor.of("codeExecution", () -> {
            var service = codeExecution.getIfAvailable();
            return service == null ? null : service.status();
        });
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    CodeRunRepository codeRunRepository(ObjectProvider<MongoTemplate> mongo) {
        return new MongoCodeRunRepository(mongo.getObject());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    CodeArtifactRepository codeArtifactRepository(ObjectProvider<MongoTemplate> mongo) {
        return new MongoCodeArtifactRepository(mongo.getObject());
    }

    @Bean
    @ConditionalOnMissingBean
    SandboxClient sandboxClient(CodeExecutionProperties properties) {
        return new DockerSandboxClient(
                properties.dockerExecutable(),
                properties.image(),
                properties.installationId());
    }

    @Bean
    @ConditionalOnMissingBean
    CodeArtifactService codeArtifactService(
            @Lazy CodeArtifactRepository artifacts,
            @Lazy ResearchRepository research,
            ExecutionRepository executions,
            @Lazy AccountRepository accounts,
            Clock clock) {
        return new CodeArtifactService(artifacts, research, executions, accounts, clock);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    CodeExecutionService codeExecutionService(
            CodeExecutionProperties properties,
            SandboxClient sandbox,
            @Lazy CodeRunRepository runs,
            CodeArtifactService artifacts,
            ExecutionRepository executions,
            @Lazy AccountRepository accounts,
            AuthorizationPolicy authorization,
            Clock clock) {
        return new CodeExecutionService(
                properties,
                sandbox,
                runs,
                artifacts,
                executions,
                accounts,
                authorization,
                clock);
    }

    /** Lets {@code task_attach_file} copy a code run output artifact of the current task into a deliverable file. */
    @Bean
    @Order(600)
    OutputFileSource codeArtifactOutputFiles(
            CodeExecutionProperties properties,
            CodeArtifactService artifacts) {
        return (context, sourceId) -> properties.enabled()
                ? artifacts.outputFile(context, sourceId)
                : java.util.Optional.empty();
    }

    @Bean
    @Order(600)
    ToolProvider codeExecutionToolProvider(
            CodeExecutionProperties properties,
            CodeExecutionService codeExecution) {
        return registry -> {
            if (properties.enabled()) {
                registry.register(CodeExecutionTools.definition(codeExecution));
            }
        };
    }

    @Bean
    @Order(200)
    GatewayCustomizer codeExecutionGatewayCustomizer(CodeExecutionService codeExecution) {
        return gateway -> {
            gateway.registerSettlementCheck(codeExecution::settled);
            gateway.registerDeletionBarrier(codeExecution::deleteExecution);
        };
    }

    @Bean
    @Order(300)
    ContextContributor codeExecutionGuidance(ToolRegistry tools) {
        return ToolGuidanceContributor
                .whenRegistered(tools, CodeExecutionPrompts.CODE_EXECUTION_INSTRUCTIONS, "code_run");
    }
}
