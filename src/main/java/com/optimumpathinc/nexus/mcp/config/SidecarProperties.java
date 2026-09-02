package com.optimumpathinc.nexus.mcp.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the sidecar, now a pure MCP Gateway / Resource Server.
 *
 * <p>Auth model (On-Behalf-Of):
 * <ul>
 *   <li>The {@code /mcp} Resource Server validates {@code MCP_ACCESS_TOKEN}s issued by an
 *       EXTERNAL Authorization Server (master), verifying the signature against the AS
 *       {@code jwks_uri} plus {@code iss}/{@code aud}/{@code exp}.</li>
 *   <li>Leg B (OBO) — the validated access token is forwarded unchanged to the routed backend MCP
 *       server as a {@code Bearer} JWT so that backend rebuilds the real user's context from
 *       {@code sub} and applies its own RBAC.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "sidecar")
public class SidecarProperties {

    /**
     * External Authorization Server (master) issuer URL. Used to discover the {@code jwks_uri}
     * (when {@code jwksUri} is blank), to validate the {@code iss} claim of incoming access tokens,
     * and to advertise {@code authorization_servers} in the protected-resource metadata.
     */
    @NotBlank
    private String issuerUri = "http://localhost:8090";

    /**
     * Optional explicit JWKS endpoint of the external Authorization Server. When blank, the decoder
     * discovers it from {@code issuerUri} via OIDC/RFC 8414 metadata.
     */
    private String jwksUri = "";

    /**
     * The MCP resource identifier (RFC 8707) this gateway represents. Incoming access tokens MUST
     * carry it in {@code aud}; also published as {@code resource} in the protected-resource metadata
     * and used to build the resource-metadata pointer URL. Must be a URL served by this sidecar.
     */
    @NotBlank
    private String mcpResource = "http://localhost:8090/mcp";

    /** Allowed clock skew when validating {@code exp}/{@code nbf} on incoming access tokens. */
    private Duration clockSkew = Duration.ofSeconds(60);

    /**
     * Static registry of backend MCP servers this gateway aggregates (D13). Validated at startup by
     * {@code BackendRegistry}: prefixes are required, unique (case-sensitive), match
     * {@code [a-zA-Z0-9_-]+} and must not contain {@code __}; URLs must be absolute. An entry whose
     * backend is unreachable does NOT fail startup, because discovery is lazy (D10).
     */
    private List<Backend> backends = new ArrayList<>();

    /**
     * Freshness threshold for the aggregated tool catalog (D10). This is a request-driven threshold,
     * not a background schedule: once it has elapsed, the next authenticated {@code tools/list} or
     * {@code tools/call} refreshes the catalog with that request's token.
     */
    private Duration catalogTtl = Duration.ofMinutes(10);

    /**
     * Minimum delay before another discovery/refresh is attempted after a failed one (D11). During
     * the backoff window a stale or partial catalog is served instead of retrying the backends.
     */
    private Duration catalogFailureBackoff = Duration.ofSeconds(30);

    /** Connect timeout for outbound MCP calls to backend servers, so a hung backend fails fast. */
    private Duration backendConnectTimeout = Duration.ofSeconds(5);

    /** Read (response) timeout for outbound MCP calls to backend servers. */
    private Duration backendReadTimeout = Duration.ofSeconds(15);

    /** A single backend MCP server entry in the static registry. */
    public static class Backend {

        /** External namespace prefix, joined to the native tool name with {@code __}. */
        private String prefix;

        /** Absolute base URL of the backend's Streamable HTTP MCP endpoint. */
        private String url;

        public Backend() {
        }

        public Backend(String prefix, String url) {
            this.prefix = prefix;
            this.url = url;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    public String getMcpResource() {
        return mcpResource;
    }

    public void setMcpResource(String mcpResource) {
        this.mcpResource = mcpResource;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }

    public List<Backend> getBackends() {
        return backends;
    }

    public void setBackends(List<Backend> backends) {
        this.backends = backends;
    }

    public Duration getCatalogTtl() {
        return catalogTtl;
    }

    public void setCatalogTtl(Duration catalogTtl) {
        this.catalogTtl = catalogTtl;
    }

    public Duration getCatalogFailureBackoff() {
        return catalogFailureBackoff;
    }

    public void setCatalogFailureBackoff(Duration catalogFailureBackoff) {
        this.catalogFailureBackoff = catalogFailureBackoff;
    }

    public Duration getBackendConnectTimeout() {
        return backendConnectTimeout;
    }

    public void setBackendConnectTimeout(Duration backendConnectTimeout) {
        this.backendConnectTimeout = backendConnectTimeout;
    }

    public Duration getBackendReadTimeout() {
        return backendReadTimeout;
    }

    public void setBackendReadTimeout(Duration backendReadTimeout) {
        this.backendReadTimeout = backendReadTimeout;
    }
}
