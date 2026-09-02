package com.optimumpathinc.nexus.mcp.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Task 7.17 — the gateway starts without contacting any backend, and neither an unauthenticated MCP
 * request nor a metadata request triggers backend discovery. A configured but unreachable backend
 * must not prevent startup.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // An explicit JWKS URI avoids OIDC discovery I/O; keys are fetched lazily on first use.
        "sidecar.jwks-uri=http://localhost:9/jwks.json",
        "sidecar.issuer-uri=https://auth.blss.local",
        "sidecar.mcp-resource=https://mcp.blss.local/mcp",
        "sidecar.backends[0].prefix=blss",
        "sidecar.backends[0].url=http://unreachable.invalid:9/mcp"
})
class GatewayStartupTest {

    @Autowired
    private BackendRegistry registry;

    @Autowired
    private ToolCatalogService catalogService;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void startsWithTheStaticRegistryAndAnUninitializedCatalog() {
        assertThat(registry.backends()).singleElement()
                .satisfies(backend -> assertThat(backend.prefix()).isEqualTo("blss"));
        // No initialize/tools/list happened at startup: discovery is request-driven.
        assertThat(catalogService.current()).isNull();
    }

    @Test
    void anUnauthenticatedMcpRequestIsChallengedAndTriggersNoDiscovery() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        containsString("resource_metadata=")));

        assertThat(catalogService.current()).isNull();
    }

    @Test
    void theProtectedResourceMetadataStaysPubliclyReadableAndTriggersNoDiscovery() throws Exception {
        mvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorization_servers[0]").value("https://auth.blss.local"))
                .andExpect(jsonPath("$.resource").value("https://mcp.blss.local/mcp"))
                .andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"))
                .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false))
                .andExpect(jsonPath("$.scopes_supported").doesNotExist());

        // The path-suffixed form MCP clients derive from the resource URL resolves too.
        mvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorization_servers[0]").value("https://auth.blss.local"));

        assertThat(catalogService.current()).isNull();
    }
}
