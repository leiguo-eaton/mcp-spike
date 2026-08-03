package com.optimumpathinc.nexus.mcp.security;

import com.optimumpathinc.nexus.mcp.config.SidecarProperties;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728).
 *
 * <p>Advertises which Authorization Server protects the MCP endpoint. The MCP client fetches this
 * after receiving the {@code WWW-Authenticate: Bearer resource_metadata="..."} challenge from
 * {@link McpAuthenticationEntryPoint}. Here the AS is the sidecar itself ({@code issuer-uri}).
 */
@RestController
public class ProtectedResourceMetadataController {

    private final SidecarProperties props;

    public ProtectedResourceMetadataController(SidecarProperties props) {
        this.props = props;
    }

    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"})
    public Map<String, Object> metadata() {
        String issuer = props.getIssuerUri();
        return Map.of(
                "resource", issuer + "/mcp",
                "authorization_servers", List.of(issuer),
                "bearer_methods_supported", List.of("header"),
                "scopes_supported", List.of("mcp.read", "mcp.invoke"));
    }
}
