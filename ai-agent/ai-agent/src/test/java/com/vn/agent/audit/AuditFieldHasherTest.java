package com.vn.agent.audit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class AuditFieldHasherTest {

    @Test
    void nullInput_returnsNull() {
        assertThat(AuditFieldHasher.sha256Hex(null)).isNull();
    }

    @Test
    void emptyString_returnsKnownEmptySha256Hex() {
        // RFC-standard SHA-256 of empty input.
        assertThat(AuditFieldHasher.sha256Hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void asciiFixture_matchesRfcVector() {
        assertThat(AuditFieldHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void vietnameseUtf8_isByteStable() throws Exception {
        String input = "Hoạt động";
        byte[] expectedDigest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
        String expectedHex = HexFormat.of().formatHex(expectedDigest);
        assertThat(AuditFieldHasher.sha256Hex(input)).isEqualTo(expectedHex);
        assertThat(AuditFieldHasher.sha256Hex(input)).hasSize(64);
    }

    @Test
    void output_isLowercaseHex64Chars() {
        String hex = AuditFieldHasher.sha256Hex("any-non-null-input");
        assertThat(hex).hasSize(64);
        assertThat(hex).matches("[0-9a-f]{64}");
    }

    @Test
    void deterministic_sameInputSameOutput() {
        assertThat(AuditFieldHasher.sha256Hex("ai-agent"))
                .isEqualTo(AuditFieldHasher.sha256Hex("ai-agent"));
    }

    @Test
    void utility_hasNoPublicConstructor() {
        assertThat(java.lang.reflect.Modifier.isFinal(AuditFieldHasher.class.getModifiers())).isTrue();
        assertThat(AuditFieldHasher.class.getConstructors()).isEmpty();
    }
}
