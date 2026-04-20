package com.vn.agent.rag;

import com.vn.agent.rag.config.AiAgentRagProperties;
import com.vn.agent.spi.CustomIngester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sample {@link CustomIngester} that scans a classpath pattern for markdown / other
 * Tika-readable files and exposes them to the {@link IngesterManager} pipeline.
 *
 * <p><b>Disabled by default</b> ({@code @ConditionalOnProperty havingValue = "true"}).
 * Hosts opt in via {@code jmix.ai-agent.rag.sample-ingester.enabled=true}; the
 * path pattern is tunable via {@code jmix.ai-agent.rag.sample-ingester.path-pattern}
 * (default {@code classpath*:ai-kb/**&#47;*.md}).
 *
 * <p>Each matched resource becomes a {@link Document} whose {@code metadata.source}
 * is set to the resolved classpath URI — {@link IngesterManager} forwards that URI
 * verbatim into {@link KnowledgeDocumentUploadService#upload}, so the
 * {@link AsyncIngestionWorker} resolves it via Spring's {@code ResourceLoader}
 * (the {@code classpath:} prefix short-circuits the default {@code ai-kb/} root).
 *
 * <p><b>D-14 opt-in:</b> this ingester does not execute on startup; it runs only when
 * {@link IngesterManager#runAll()} is invoked by host admin action.
 */
@Component
@ConditionalOnProperty(name = "jmix.ai-agent.rag.sample-ingester.enabled", havingValue = "true")
public class ClasspathMarkdownIngester implements CustomIngester {

    private static final Logger log = LoggerFactory.getLogger(ClasspathMarkdownIngester.class);

    private static final String DEFAULT_PATH_PATTERN = "classpath*:ai-kb/**/*.md";

    private final AiAgentRagProperties ragProperties;
    private final ResourcePatternResolver resourcePatternResolver;

    public ClasspathMarkdownIngester(AiAgentRagProperties ragProperties,
                                     ResourcePatternResolver resourcePatternResolver) {
        this.ragProperties = ragProperties;
        this.resourcePatternResolver = resourcePatternResolver == null
                ? new PathMatchingResourcePatternResolver()
                : resourcePatternResolver;
    }

    @Override
    public String getId() {
        return "classpath-markdown-sample";
    }

    @Override
    public String getDisplayName() {
        return "Built-in classpath markdown sample ingester";
    }

    @Override
    public List<Document> read() {
        String pattern = resolvePattern();
        Resource[] resources;
        try {
            resources = resourcePatternResolver.getResources(pattern);
        } catch (IOException e) {
            log.error("ClasspathMarkdownIngester: failed to resolve pattern {}: {}",
                    pattern, e.getMessage(), e);
            return List.of();
        }
        List<Document> out = new ArrayList<>();
        for (Resource resource : resources) {
            try {
                List<Document> docs = new TikaDocumentReader(resource).get();
                String source = resource.getURI().toString();
                for (Document doc : docs) {
                    Map<String, Object> md = new HashMap<>();
                    if (doc.getMetadata() != null) {
                        md.putAll(doc.getMetadata());
                    }
                    md.put(ChunkMetadata.SOURCE, source);
                    out.add(doc.mutate().metadata(md).build());
                }
            } catch (Exception perResource) {
                log.warn("ClasspathMarkdownIngester: skipping {} due to read failure: {}",
                        safeDescription(resource), perResource.getMessage());
            }
        }
        return out;
    }

    private String resolvePattern() {
        AiAgentRagProperties.SampleIngester cfg = ragProperties.sampleIngester();
        if (cfg != null && cfg.pathPattern() != null && !cfg.pathPattern().isBlank()) {
            return cfg.pathPattern();
        }
        return DEFAULT_PATH_PATTERN;
    }

    private static String safeDescription(Resource resource) {
        try {
            return resource.getDescription();
        } catch (Exception ignore) {
            return "<unknown-resource>";
        }
    }
}
