package com.optimumpathinc.nexus.mcp.security;

import com.optimumpathinc.nexus.mcp.config.SidecarProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Resource-server entry point for the {@code /mcp} endpoint.
 *
 * <p>On an unauthenticated request it returns {@code 401} with a
 * {@code WWW-Authenticate: Bearer resource_metadata="..."} header (RFC 9728). This is the signal
 * the MCP client uses to discover the (external) Authorization Server and start the OAuth flow.
 * The pointer targets THIS sidecar's protected-resource metadata, derived from the configured MCP
 * resource's origin.
 */
public class McpAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final String resourceMetadataUrl;

    public McpAuthenticationEntryPoint(SidecarProperties props) {
        this.resourceMetadataUrl = originOf(props.getMcpResource())
                + "/.well-known/oauth-protected-resource";
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer resource_metadata=\"" + resourceMetadataUrl + "\"");
        response.sendError(HttpStatus.UNAUTHORIZED.value(), "Unauthorized");
    }

    /** Returns the scheme://host[:port] origin of the given URL, without any path. */
    private static String originOf(String url) {
        URI uri = URI.create(url);
        StringBuilder origin = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() != -1) {
            origin.append(':').append(uri.getPort());
        }
        return origin.toString();
    }
}
