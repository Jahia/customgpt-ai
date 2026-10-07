package org.jahia.community.modules.customgpt.indexer;

import java.nio.charset.StandardCharsets;
import okhttp3.MediaType;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests how a binary file is prepared for upload to CustomGPT.
 *
 * <p>Files and rendered pages share one indexing path, and that path used to read every response body with
 * {@code body().string()} and upload {@code output.getBytes(UTF_8)} as {@code text/html}. For a PDF that is a
 * UTF-8 decode-and-re-encode: every byte sequence that is not valid UTF-8 becomes U+FFFD. Measured on the
 * Digitall sample, a 155,468-byte PDF came out at 264,461 bytes with 154,804 of its bytes changed - only the
 * {@code %PDF-} header survived. Uploads succeeded, so nothing ever reported it.
 *
 * <p>JUnit 4: the jahia-modules parent pins the {@code surefire-junit4} provider.
 */
public class FileUploadTest {

    // ---- the corruption these changes exist to prevent ----

    @Test
    public void utf8RoundTrip_destroysBinaryContent() {
        // A PDF header followed by bytes that are not valid UTF-8, which is every real PDF's body.
        final byte[] pdf = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4', '\n',
            (byte) 0x80, (byte) 0xFF, (byte) 0xC3, (byte) 0x28, (byte) 0xA9, (byte) 0xE2, (byte) 0x82};

        final byte[] roundTripped = new String(pdf, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);

        assertThat(roundTripped).isNotEqualTo(pdf);
        assertThat(roundTripped.length).isGreaterThan(pdf.length);
    }

    // ---- media type ----

    @Test
    public void uploadMediaType_isAlwaysHtmlForARenderedPage() {
        final MediaType type = CustomGptIndexerNodeHandler.uploadMediaType(false, MediaType.parse("application/pdf"), "application/pdf");

        assertThat(type.toString()).startsWith("text/html");
    }

    @Test
    public void uploadMediaType_keepsWhatJahiaServedForAFile() {
        // This is what tells CustomGPT how to extract text; announcing a PDF as text/html asks it to parse the
        // bytes as markup.
        final MediaType type = CustomGptIndexerNodeHandler.uploadMediaType(true, MediaType.parse("application/pdf"), null);

        assertThat(type.toString()).isEqualTo("application/pdf");
    }

    @Test
    public void uploadMediaType_fallsBackToTheNodeMimeTypeWhenTheResponseNamesNone() {
        final MediaType type = CustomGptIndexerNodeHandler.uploadMediaType(true, null, "application/pdf");

        assertThat(type.toString()).isEqualTo("application/pdf");
    }

    @Test
    public void uploadMediaType_fallsBackToOctetStreamWhenNothingNamesOne() {
        assertThat(CustomGptIndexerNodeHandler.uploadMediaType(true, null, null).toString())
                .isEqualTo("application/octet-stream");
        assertThat(CustomGptIndexerNodeHandler.uploadMediaType(true, null, "").toString())
                .isEqualTo("application/octet-stream");
    }

    @Test
    public void uploadMediaType_fallsBackToOctetStreamWhenTheNodeMimeTypeIsUnparseable() {
        assertThat(CustomGptIndexerNodeHandler.uploadMediaType(true, null, "not a media type").toString())
                .isEqualTo("application/octet-stream");
    }

    // ---- multipart file name ----

    @Test
    public void uploadFileName_usesTheNodeNameForAFileSoTheExtensionSurvives() {
        // The display title carries no extension: "Digitall Financial Report" tells CustomGPT nothing about
        // what it is being handed.
        final String name = CustomGptIndexerNodeHandler.uploadFileName(
                true, "Digitall Financial Report.pdf", "Digitall Financial Report");

        assertThat(name).isEqualTo("Digitall Financial Report.pdf");
    }

    @Test
    public void uploadFileName_usesTheTitleForARenderedPage() {
        final String name = CustomGptIndexerNodeHandler.uploadFileName(false, "home", "Home page");

        assertThat(name).isEqualTo("Home page");
    }

    @Test
    public void uploadFileName_fallsBackToTheTitleWhenAFileHasNoName() {
        assertThat(CustomGptIndexerNodeHandler.uploadFileName(true, "", "Some title")).isEqualTo("Some title");
        assertThat(CustomGptIndexerNodeHandler.uploadFileName(true, null, "Some title")).isEqualTo("Some title");
    }
}
