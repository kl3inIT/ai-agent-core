package com.vn.agent.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stateless SHA-256-over-UTF-8 hex hasher used by {@code AuditWriter} (Phase 11+) when
 * {@code jmix.ai-agent.audit.hash-sensitive-fields} is {@code true}, to redact configured
 * sensitive fields in mutation pre/post-image audit payloads (AUD-07).
 *
 * <p><b>Phase 9 plumbing only (D-18):</b> this class has zero call sites in v1.1's
 * read-only milestone. Phase 11's {@code MutationErrorTranslator} / pre/post-image diff is the
 * planned consumer. The utility ships now so Phase 11 wiring is a one-liner.
 *
 * <p>NOT a Spring component, NOT an SPI. SPI extraction is deferred until a concrete host
 * requests non-SHA-256 hashing (project memory {@code feedback_spi_baseline_builtin}).
 *
 * <p>Output is lowercase hex (matches {@link HexFormat#of()} default) so audit consumers can
 * compare hashes byte-for-byte across deployments and locales.
 */
public final class AuditFieldHasher {

    private AuditFieldHasher() {
    }

    /**
     * @param raw the value to hash; {@code null} returns {@code null}
     * @return 64-character lowercase hex of SHA-256 over the UTF-8 byte encoding of {@code raw}
     */
    public static String sha256Hex(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java SE 8+ runtime; if absent the JVM is broken.
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
