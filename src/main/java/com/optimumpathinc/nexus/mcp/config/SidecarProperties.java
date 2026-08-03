package com.optimumpathinc.nexus.mcp.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the sidecar.
 *
 * <p>Two auth legs (see doc/mcp appendix B):
 * <ul>
 *   <li>Leg A — Nexus -> sidecar service trust: static API key ({@code apiKey}).</li>
 *   <li>Leg B — sidecar -> master: the end-user delegation token is passed through
 *       from the inbound MCP request header ({@code userTokenHeader}) to master REST.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "sidecar")
public class SidecarProperties {

    /** Base URL of BLSS master REST API, e.g. https://master.example.com/bldc-blss-master. */
    @NotBlank
    private String masterBaseUrl;

    /** Shared secret that Nexus must present (Leg A). Compared against the X-API-Key header. */
    @NotBlank
    private String apiKey;

    /** Header carrying the Leg A service API key. */
    private String apiKeyHeader = "X-API-Key";

    /** Inbound header carrying the end-user delegation token (Leg B, on-behalf-of). */
    private String userTokenHeader = "X-BLSS-User-Token";

    /** Outbound header the sidecar sets when forwarding the delegation token to master REST. */
    private String masterAuthHeader = "Authorization";

    /**
     * Leg B (on-behalf-of) — fixed BLSS delegation token (Base64 of {@code user:password}) embedded
     * into the issued access-token JWT as the {@code blss_token} claim, then forwarded by
     * {@code MasterClient} to master REST as {@code Basic} auth. POC only: later this can be replaced
     * by a per-user value derived at login without touching MasterClient.
     */
    private String blssUserToken = "";

    /**
     * Leg A (OAuth 2.1) — the public base URL of this sidecar, used as the OAuth issuer and to
     * build the {@code resource} / {@code authorization_servers} values in the protected-resource
     * metadata. Must match the URL the MCP client uses to reach the sidecar.
     */
    @NotBlank
    private String issuerUri = "http://localhost:8090";

    /** Leg A (OAuth 2.1) — demo resource-owner username for the interactive login (POC only). */
    @NotBlank
    private String authUsername = "mcp";

    /** Leg A (OAuth 2.1) — demo resource-owner password for the interactive login (POC only). */
    @NotBlank
    private String authPassword = "mcp-pass";

    /**
     * Leg A (OAuth 2.1) — public (PKCE, no-secret) client_ids to pre-seed into the registered-client
     * store on startup. Use this to recover a client_id that an MCP client (e.g. VS Code) cached from
     * an earlier in-memory registration that was lost on restart, since such clients do not
     * re-register on their own. Each is created with the MCP scopes and the standard loopback
     * redirect URIs. Safe to leave empty once clients register dynamically against the persistent store.
     */
    private List<String> seedPublicClientIds = new ArrayList<>();

    /**
     * Connect timeout for outbound calls to master REST. Bounds how long {@code MasterClient} waits
     * to establish a TCP connection before failing fast, so a hung/slow master cannot stall a tool
     * call indefinitely (which otherwise races with the MCP client tearing down the session).
     */
    private Duration masterConnectTimeout = Duration.ofSeconds(5);

    /**
     * Read (response) timeout for outbound calls to master REST. Bounds how long {@code MasterClient}
     * waits for the master response before failing fast.
     */
    private Duration masterReadTimeout = Duration.ofSeconds(15);

    public String getMasterBaseUrl() {
        return masterBaseUrl;
    }

    public void setMasterBaseUrl(String masterBaseUrl) {
        this.masterBaseUrl = masterBaseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKeyHeader() {
        return apiKeyHeader;
    }

    public void setApiKeyHeader(String apiKeyHeader) {
        this.apiKeyHeader = apiKeyHeader;
    }

    public String getUserTokenHeader() {
        return userTokenHeader;
    }

    public void setUserTokenHeader(String userTokenHeader) {
        this.userTokenHeader = userTokenHeader;
    }

    public String getMasterAuthHeader() {
        return masterAuthHeader;
    }

    public void setMasterAuthHeader(String masterAuthHeader) {
        this.masterAuthHeader = masterAuthHeader;
    }

    public String getBlssUserToken() {
        return blssUserToken;
    }

    public void setBlssUserToken(String blssUserToken) {
        this.blssUserToken = blssUserToken;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getAuthUsername() {
        return authUsername;
    }

    public void setAuthUsername(String authUsername) {
        this.authUsername = authUsername;
    }

    public String getAuthPassword() {
        return authPassword;
    }

    public void setAuthPassword(String authPassword) {
        this.authPassword = authPassword;
    }

    public List<String> getSeedPublicClientIds() {
        return seedPublicClientIds;
    }

    public void setSeedPublicClientIds(List<String> seedPublicClientIds) {
        this.seedPublicClientIds = seedPublicClientIds;
    }

    public Duration getMasterConnectTimeout() {
        return masterConnectTimeout;
    }

    public void setMasterConnectTimeout(Duration masterConnectTimeout) {
        this.masterConnectTimeout = masterConnectTimeout;
    }

    public Duration getMasterReadTimeout() {
        return masterReadTimeout;
    }

    public void setMasterReadTimeout(Duration masterReadTimeout) {
        this.masterReadTimeout = masterReadTimeout;
    }
}