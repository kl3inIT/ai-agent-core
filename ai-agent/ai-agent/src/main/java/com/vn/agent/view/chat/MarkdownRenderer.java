package com.vn.agent.view.chat;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders untrusted markdown produced by the LLM into sanitized HTML suitable
 * for embedding inside a Vaadin {@code Html} component.
 *
 * <p>Thread-safe after construction: Flexmark {@link Parser} and
 * {@link HtmlRenderer} are immutable after build, and OWASP
 * {@link PolicyFactory} is explicitly thread-safe. A single Spring singleton
 * bean is therefore reused across all UIs and streaming ticks (RESEARCH
 * §Standard Stack / Pitfall #5: Flexmark does not sanitize — caller must).
 *
 * <p>The composed policy strips {@code <script>} tags, {@code javascript:}
 * URIs, {@code data:} URIs, and any {@code on*} event-handler attributes
 * (RESEARCH Pitfall #5 + Pitfall #6). Only formatting, blocks, links, and
 * GFM-style tables survive — enough for chat-assistant markdown and tabular
 * tool summaries, nothing that can execute in the browser.
 */
@Component
public class MarkdownRenderer {

    private final Parser parser;
    private final HtmlRenderer renderer;
    private final PolicyFactory sanitizer;

    public MarkdownRenderer() {
        MutableDataSet opts = new MutableDataSet();
        opts.set(Parser.EXTENSIONS, List.of(
                TablesExtension.create(),
                AutolinkExtension.create()));
        this.parser = Parser.builder(opts).build();
        this.renderer = HtmlRenderer.builder(opts).build();
        this.sanitizer = Sanitizers.FORMATTING
                .and(Sanitizers.BLOCKS)
                .and(Sanitizers.LINKS)
                .and(Sanitizers.TABLES);
    }

    /**
     * Converts a markdown fragment to sanitized HTML. {@code null} or empty
     * input yields an empty string — callers can safely bind the output to a
     * Vaadin {@code Html} element without additional null checks.
     *
     * @param markdown untrusted markdown (may be {@code null})
     * @return sanitized HTML — never {@code null}
     */
    public String toSafeHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        String rawHtml = renderer.render(parser.parse(markdown));
        return sanitizer.sanitize(rawHtml);
    }
}
