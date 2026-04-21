package com.vn.agent.view.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 07-07b Task 1 — OWASP-policy XSS defence contract for
 * {@link MarkdownRenderer#toSafeHtml(String)} (D-07, RESEARCH Pitfall #5 / #6).
 *
 * <p>Uses the real class directly — no Spring context required. The renderer is
 * constructor-less state (Parser / HtmlRenderer / PolicyFactory all built inside
 * the default constructor), so a fresh instance is sufficient.
 */
class MarkdownRendererXssTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void stripsScriptTags() {
        String out = renderer.toSafeHtml("<script>alert(1)</script>hi");
        assertThat(out).doesNotContain("<script>").doesNotContain("</script>");
    }

    @Test
    void stripsJavascriptUri() {
        String out = renderer.toSafeHtml("[evil](javascript:alert(1))");
        assertThat(out).doesNotContain("javascript:");
    }

    @Test
    void stripsDataUri() {
        String out = renderer.toSafeHtml("[evil](data:text/html;base64,PHNjcmlwdD4=)");
        assertThat(out).doesNotContain("data:");
    }

    @Test
    void stripsOnErrorHandler() {
        String out = renderer.toSafeHtml("<img src=x onerror=alert(1)>");
        assertThat(out).doesNotContain("onerror").doesNotContain("alert");
    }

    @Test
    void preservesSafeFormatting() {
        String out = renderer.toSafeHtml("**bold** and _italic_ and `code`");
        assertThat(out)
                .contains("<strong>")
                .contains("<em>")
                .contains("<code>");
    }

    @Test
    void preservesSafeLinks() {
        String out = renderer.toSafeHtml("[docs](https://jmix.io)");
        assertThat(out).contains("https://jmix.io");
    }

    @Test
    void nullOrEmptyReturnsEmpty() {
        assertThat(renderer.toSafeHtml(null)).isEmpty();
        assertThat(renderer.toSafeHtml("")).isEmpty();
    }
}
