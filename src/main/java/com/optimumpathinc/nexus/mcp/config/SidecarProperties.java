package com.optimumpathinc.nexus.mcp.config;

import jakarta.validation.constraints.NotBlank;
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
}
