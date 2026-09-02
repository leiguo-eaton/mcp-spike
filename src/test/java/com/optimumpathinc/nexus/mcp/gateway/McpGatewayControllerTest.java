package com.optimumpathinc.nexus.mcp.gateway;

import static com.optimumpathinc.nexus.mcp.gateway.GatewayTestSupport.RecordingBackendMcpClient;
import static com.optimumpathinc.nexus.mcp.gateway.GatewayTestSupport.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Tasks 7.19 (capability scope) and 7.16 (routing seen end-to-end through the MCP endpoint).
 *
 * <p>The gateway advertises and serves only {@code tools}; every other capability is answered with
 * "method not found" and is never forwarded to a backend.
 */
class McpGatewayControllerTest {

    private static final String USER_TOKEN = "user-access-token";

    private RecordingBackendMcpClient backends;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        backends = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"))
                .withCatalog("superset", tool("run_sql"));
        BackendRegistry registry = GatewayTestSupport.registry(
                "blss", "http://blss.local/mcp",
                "superset", "http://superset.local/mcp");
        ToolCatalogService catalog = new ToolCatalogService(registry, backends,
                Duration.ofMinutes(10), Duration.ofSeconds(30), Clock.systemUTC());
        GatewayToolService tools = new GatewayToolService(registry, catalog, backends);

        mvc = MockMvcBuilders
                .standaloneSetup(new McpGatewayController(tools, GatewayTestSupport.MAPPER))
                .build();

        Jwt jwt = Jwt.withTokenValue(USER_TOKEN)
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("sub", "peter")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void initializeAdvertisesOnlyTheToolsCapability() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"" + McpProtocol.VERSION + "\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.capabilities.tools").exists())
                .andExpect(jsonPath("$.result.capabilities.resources").doesNotExist())
                .andExpect(jsonPath("$.result.capabilities.prompts").doesNotExist())
                .andExpect(jsonPath("$.result.capabilities.logging").doesNotExist())
                .andExpect(jsonPath("$.result.capabilities.completions").doesNotExist())
                .andExpect(jsonPath("$.result.serverInfo.name").value(McpProtocol.SERVER_NAME));
    }

    @Test
    void theInitializeHandshakeTriggersNoBackendDiscovery() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.result.protocolVersion").value(McpProtocol.VERSION));

        assertThat(backends.discoveries).hasValue(0);
    }

    @Test
    void toolsListServesTheAggregatedNamespacedCatalog() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[0].name").value("blss__query_asset"))
                .andExpect(jsonPath("$.result.tools[1].name").value("superset__run_sql"));

        assertThat(backends.discoveryTokens).containsOnly(USER_TOKEN);
    }

    @Test
    void toolsCallIsRoutedDePrefixedAndForwardsTheValidatedToken() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"blss__query_asset\",\"arguments\":{\"id\":1}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist());

        assertThat(backends.calledNativeNames).containsExactly("query_asset");
        assertThat(backends.callTokens).containsExactly(USER_TOKEN);
    }

    @Test
    void anUnknownPrefixReturnsTheStableCategoryWithoutDiscovery() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"nope__do_thing\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.data.category")
                        .value(GatewayErrorCode.UNKNOWN_PREFIX.category()))
                .andExpect(jsonPath("$.result").doesNotExist());

        assertThat(backends.discoveries).hasValue(0);
    }

    @Test
    void nonToolsCapabilityRequestsAreNotRoutedDownstream() throws Exception {
        for (String method : new String[] {"resources/list", "resources/templates/list", "prompts/list",
                "completion/complete", "logging/setLevel"}) {
            mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"" + method + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.error.code").value(JsonRpc.METHOD_NOT_FOUND));
        }

        assertThat(backends.discoveries).hasValue(0);
        assertThat(backends.calledNativeNames).isEmpty();
    }

    @Test
    void notificationsAreAcknowledgedWithoutABody() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        assertThat(backends.discoveries).hasValue(0);
    }

    @Test
    void pingIsAnsweredWithoutTouchingTheCatalog() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").exists());

        assertThat(backends.discoveries).hasValue(0);
    }

    @Test
    void theGatewayOpensNoServerStreamAndKeepsNoSessionState() throws Exception {
        mvc.perform(get("/mcp")).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/mcp")).andExpect(status().isNoContent());
    }

    private static org.springframework.test.web.servlet.RequestBuilder rpc(String body) {
        return post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
