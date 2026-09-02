package com.optimumpathinc.nexus.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.optimumpathinc.nexus.mcp.config.SidecarProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

/**
 * The {@code 401} challenge points MCP clients at this gateway's protected-resource metadata.
 *
 * <p>The metadata document itself is served by Spring Security's RFC 9728 filter and is asserted
 * end-to-end in {@code GatewayStartupTest}.
 */
class GatewayChallengeAndMetadataTest {

    private static final String ISSUER = "https://master.company.com";
    private static final String MCP_RESOURCE = "https://mcp.company.com/mcp";

    private SidecarProperties props() {
        SidecarProperties props = new SidecarProperties();
        props.setIssuerUri(ISSUER);
        props.setMcpResource(MCP_RESOURCE);
        return props;
    }

    @Test
    void challengeReturns401WithResourceMetadataPointerToThisSidecar() throws Exception {
        McpAuthenticationEntryPoint entryPoint = new McpAuthenticationEntryPoint(props());
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest(), response,
                new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        String header = response.getHeader(HttpHeaders.WWW_AUTHENTICATE);
        assertThat(header).isNotNull();
        // Pointer targets THIS sidecar's well-known (derived from the MCP resource origin), not the AS.
        assertThat(header).contains(
                "resource_metadata=\"https://mcp.company.com/.well-known/oauth-protected-resource\"");
    }
}
