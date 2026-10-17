package net.velocity.agent.research;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;

import net.velocity.agent.ToolRegistry;
import net.velocity.agent.config.UploadProperties;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.security.OperatorRoutes;
import net.velocity.agent.security.ReservedDestinations;
import net.velocity.agent.spi.ContextContributor;
import net.velocity.agent.spi.GatewayCustomizer;
import net.velocity.agent.spi.OutputFileSource;
import net.velocity.agent.spi.ToolGuidanceContributor;
import net.velocity.agent.spi.ToolProvider;
import net.velocity.inference.InferenceProfileRegistry;
import net.velocity.inference.SystemPrompts;

/**
 * Task research: controlled downloads, document reading, uploads, reports and output files. The research
 * service is also the kernel's {@code AttachmentStore} and {@code ChildReferenceValidator}; the kernel picks
 * both up by type. Upload limits ({@code agent.uploads}) are kernel properties because the local operator
 * boundary enforces the request body size.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ResearchProperties.class, FileProcessingProperties.class})
public class ResearchConfiguration {

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    ResearchRepository researchRepository(ObjectProvider<MongoTemplate> mongo) {
        return new MongoResearchRepository(mongo.getObject());
    }

    @Bean
    @ConditionalOnMissingBean
    PdfPageRenderer pdfPageRenderer(FileProcessingProperties properties) {
        return new PopplerPdfPageRenderer(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    FileInferenceExtractor fileInferenceExtractor(
            PdfPageRenderer renderer,
            ResearchProperties research,
            FileProcessingProperties properties,
            InferenceProfileRegistry profiles,
            ExecutionRepository executions) {
        return new FileInferenceExtractor(
                renderer,
                research,
                properties,
                profiles,
                executions);
    }

    /** One direct-fetch policy and download concurrency limit, shared by task research and feature services. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    ControlledDownloader controlledDownloader(
            ResearchProperties properties,
            OperatorRoutes routes,
            ReservedDestinations reserved) {
        return new ControlledDownloader(properties, routes, reserved);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    ResearchService researchService(
            @Lazy ResearchRepository repository,
            ExecutionRepository executions,
            ResearchProperties properties,
            UploadProperties uploads,
            Clock clock,
            FileInferenceExtractor fileExtractor,
            ControlledDownloader downloader) {
        // Read/download APIs remain available when tools are disabled. Construction performs no source or Mongo request.
        return new ResearchService(
                repository,
                executions,
                properties,
                uploads,
                downloader,
                clock,
                fileExtractor);
    }

    /** Research, report and output-file tools, present only while research is enabled. */
    @Bean
    @Order(700)
    ToolProvider researchToolProvider(
            ResearchProperties researchProperties,
            ResearchService research,
            ExecutionRepository executions,
            ObjectProvider<OutputFileSource> outputFileSources) {
        return registry -> {
            if (researchProperties.enabled()) {
                ResearchTools.register(registry, research);
                ReportTools.register(registry, executions, research);
                OutputFileTools.register(
                        registry,
                        executions,
                        research,
                        outputFileSources.orderedStream().toList());
            }
        };
    }

    @Bean
    @Order(450)
    ContextContributor outputFileGuidance(ToolRegistry tools) {
        return ToolGuidanceContributor
                .whenRegistered(tools, SystemPrompts.OUTPUT_FILE_INSTRUCTIONS, "task_attach_file");
    }

    /** Research artifacts, uploads and output files of a deleted execution go with it. */
    @Bean
    @Order(300)
    GatewayCustomizer researchDeletionListener(ResearchService research) {
        return gateway -> gateway.registerDeletionListener(research::deleteExecution);
    }
}
