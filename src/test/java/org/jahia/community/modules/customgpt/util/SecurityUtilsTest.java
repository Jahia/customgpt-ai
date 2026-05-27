package org.jahia.community.modules.customgpt.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SecurityUtils} — the write-only-secret masking contract and the HTTPS gate that protects
 * credentials in transit. These three behaviours back the security fixes: secrets are never echoed back
 * ({@code maskSecretForDisplay}), an echoed/blank secret is never persisted over the stored one
 * ({@code isUnchangedSecret}), and credential-bearing URLs must be HTTPS ({@code isHttpsUrl}).
 */
class SecurityUtilsTest {

    @Nested
    @DisplayName("maskSecretForDisplay")
    class MaskSecretForDisplay {

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("returns empty string when no secret is stored")
        void emptyWhenNotSet(String stored) {
            assertThat(SecurityUtils.maskSecretForDisplay(stored)).isEmpty();
        }

        @Test
        @DisplayName("returns the placeholder (never the real value) when a secret is stored")
        void placeholderWhenSet() {
            assertThat(SecurityUtils.maskSecretForDisplay("sk-super-secret-token"))
                    .isEqualTo(SecurityUtils.SECRET_PLACEHOLDER)
                    .doesNotContain("secret");
        }
    }

    @Nested
    @DisplayName("isMaskedPlaceholder")
    class IsMaskedPlaceholder {

        @Test
        @DisplayName("recognises the placeholder echoed back by the UI (must not be persisted)")
        void placeholderIsRecognised() {
            assertThat(SecurityUtils.isMaskedPlaceholder(SecurityUtils.SECRET_PLACEHOLDER)).isTrue();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("null and empty are NOT the placeholder (empty still means 'clear the secret')")
        void blankIsNotPlaceholder(String incoming) {
            assertThat(SecurityUtils.isMaskedPlaceholder(incoming)).isFalse();
        }

        @Test
        @DisplayName("a genuinely new value is not the placeholder (persist it)")
        void newValueIsNotPlaceholder() {
            assertThat(SecurityUtils.isMaskedPlaceholder("a-brand-new-token")).isFalse();
        }
    }

    @Nested
    @DisplayName("isHttpsUrl")
    class IsHttpsUrl {

        @Test
        @DisplayName("accepts a well-formed https URL with a host")
        void acceptsHttps() {
            assertThat(SecurityUtils.isHttpsUrl("https://app.customgpt.ai/api/v1")).isTrue();
        }

        @Test
        @DisplayName("is case-insensitive on the scheme and tolerates surrounding whitespace")
        void acceptsHttpsCaseAndTrim() {
            assertThat(SecurityUtils.isHttpsUrl("  HTTPS://app.customgpt.ai/api/v1  ")).isTrue();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {
            "http://app.customgpt.ai/api/v1", // cleartext — token would leak on the wire
            "ftp://app.customgpt.ai",
            "https://",                       // no host
            "app.customgpt.ai/api/v1",        // no scheme
            "not a url",
            "file:///etc/passwd"
        })
        @DisplayName("rejects non-https, host-less, scheme-less and malformed URLs")
        void rejectsEverythingElse(String url) {
            assertThat(SecurityUtils.isHttpsUrl(url)).isFalse();
        }
    }
}
