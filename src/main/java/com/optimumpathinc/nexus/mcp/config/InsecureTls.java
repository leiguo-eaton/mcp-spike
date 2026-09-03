package com.optimumpathinc.nexus.mcp.config;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Builds HTTP clients that skip TLS certificate <em>and</em> hostname verification.
 *
 * <p><strong>Development / integration bring-up only.</strong> Enabled by
 * {@code sidecar.insecure-skip-tls-verify=true}. It exists solely so the gateway can talk to a
 * master that presents a self-signed certificate during E2E, before the dedicated mTLS work — which
 * will pin the exact certificate and own its lifecycle/rotation — is implemented.
 *
 * <p>NEVER enable it in production: it removes the only guarantee that the JWKS and the backend MCP
 * responses actually come from the real master, so a man-in-the-middle could serve forged signing
 * keys and defeat token validation entirely.
 */
public final class InsecureTls {

    private InsecureTls() {
    }

    /** An {@link SSLContext} whose trust manager accepts any server certificate. */
    static SSLContext trustAllContext() {
        try {
            TrustManager[] trustAll = {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build a trust-all SSLContext", e);
        }
    }

    /** A JDK {@link HttpClient} that trusts any certificate and skips hostname verification. */
    static HttpClient httpClient(Duration connectTimeout) {
        SSLParameters params = new SSLParameters();
        // A null endpoint-identification algorithm disables hostname verification, so a cert whose
        // CN/SAN does not match the requested host (e.g. an IP or an internal name) is still accepted.
        params.setEndpointIdentificationAlgorithm(null);
        return HttpClient.newBuilder()
                .sslContext(trustAllContext())
                .sslParameters(params)
                .connectTimeout(connectTimeout)
                .build();
    }

    /** A Spring request factory backed by the trust-all {@link #httpClient(Duration)}. */
    public static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient(connectTimeout));
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
