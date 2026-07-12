package org.jahia.community.modules.customgpt.testutil;

import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/**
 * Test helper that configures a {@link MockWebServer} for HTTPS with a self-signed certificate valid for the
 * hostname literal {@code "localhost"}, plus an {@link OkHttpClient.Builder} pre-configured to trust it.
 *
 * <p><b>Why this exists (do not "simplify" back to plain HTTP or a loopback IP literal):</b>
 * {@code SecurityUtils.resolveHttpsBaseUrl()} / {@code isHttpsUrl()} reject any URL whose host is a literal
 * IP in a private/loopback range as an SSRF guard, and a {@link MockWebServer} always binds to loopback. A
 * test that points the module's HTTP-client-building code at a plain {@code http://} URL, or at
 * {@code https://127.0.0.1:<port>}, will throw {@code IllegalStateException} from that guard before ever
 * reaching the mock server. The fix is exactly what this helper does: (1) serve HTTPS with a certificate
 * whose SAN covers {@code localhost}, and (2) always build the base URL string with the hostname literal
 * {@code "localhost"} (never a loopback IP literal, which IS range-checked and rejected;
 * {@code SecurityUtils.isInternalHost("localhost")} returns {@code false} because it is a hostname, not an
 * IP literal, and hostnames are never resolved/range-checked).
 */
public final class HttpsMockWebServerSupport {

    private HttpsMockWebServerSupport() {
    }

    /** A started HTTPS {@link MockWebServer} plus everything needed to talk to it and build its base URL. */
    public static final class HttpsFixture {
        public final MockWebServer server;
        public final OkHttpClient.Builder trustingClientBuilder;

        private HttpsFixture(MockWebServer server, OkHttpClient.Builder trustingClientBuilder) {
            this.server = server;
            this.trustingClientBuilder = trustingClientBuilder;
        }

        /** Base URL string using the "localhost" hostname literal — never {@code 127.0.0.1}. */
        public String baseUrl() {
            return "https://localhost:" + server.getPort();
        }

        public void shutdown() throws IOException {
            server.shutdown();
        }
    }

    public static HttpsFixture start() throws IOException {
        final HeldCertificate localhostCertificate = new HeldCertificate.Builder()
                .addSubjectAlternativeName("localhost")
                .build();
        final HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
                .heldCertificate(localhostCertificate)
                .build();
        final HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
                .addTrustedCertificate(localhostCertificate.certificate())
                .build();

        final MockWebServer server = new MockWebServer();
        server.useHttps(serverCertificates.sslSocketFactory(), false);
        server.start();

        final OkHttpClient.Builder trustingClientBuilder = new OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager());

        return new HttpsFixture(server, trustingClientBuilder);
    }
}
