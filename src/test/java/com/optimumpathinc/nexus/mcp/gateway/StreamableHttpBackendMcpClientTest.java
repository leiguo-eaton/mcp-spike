package com.optimumpathinc.nexus.mcp.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Task 7.17 — the downstream MCP client authenticates every request with the current token and
 * cleans up temporary discovery sessions.
 */
class StreamableHttpBackendMcpClientTest {

    private static final String URL = "http://blss.local/mcp";
    private static final BackendDefinition BLSS = new BackendDefinition("blss", URI.create(URL));

    private MockRestServiceServer server;
    private StreamableHttpBackendMcpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new StreamableHttpBackendMcpClient(builder.build(), GatewayTestSupport.MAPPER);
    }

    @Test
    void everyDiscoveryRequestCarriesTheCurrentBearerTokenAndTheSessionIsClosed() {
        expectInitialize("tok-a", "sess-1");
        expectInitializedNotification("tok-a", "sess-1");
        expectToolsList("tok-a", "sess-1");
        expectSessionDelete("tok-a", "sess-1");

        List<JsonNode> tools = client.listTools(BLSS, "tok-a");

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).get("name").asString()).isEqualTo("query_asset");
        server.verify();
    }

    @Test
    void aStatelessBackendNeedsNoSessionTermination() {
        // No Mcp-Session-Id in the initialize response: nothing to terminate.
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(initializeResult(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.ACCEPTED));
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(toolsListResult(), MediaType.APPLICATION_JSON));

        client.listTools(BLSS, "tok-a");

        // verify() fails if a fourth (DELETE) request had been issued.
        server.verify();
    }

    @Test
    void twoUsersEachAuthenticateTheirOwnRequests() {
        expectInitialize("tok-a", "sess-a");
        expectInitializedNotification("tok-a", "sess-a");
        expectToolsList("tok-a", "sess-a");
        expectSessionDelete("tok-a", "sess-a");
        expectInitialize("tok-b", "sess-b");
        expectInitializedNotification("tok-b", "sess-b");
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-b"))
                .andExpect(header(McpProtocol.SESSION_HEADER, "sess-b"))
                .andExpect(jsonPath("$.method").value("tools/call"))
                .andExpect(jsonPath("$.params.name").value("query_asset"))
                .andRespond(withSuccess(callResult(), MediaType.APPLICATION_JSON));
        expectSessionDelete("tok-b", "sess-b");

        client.listTools(BLSS, "tok-a");
        JsonNode result = client.callTool(BLSS, "tok-b", "query_asset", null);

        assertThat(result.get("content").get(0).get("text").asString()).isEqualTo("ok");
        server.verify();
    }

    @Test
    void serverSentEventResponsesAreParsed() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "event: message\ndata: " + initializeResult() + "\n\n", MediaType.TEXT_EVENT_STREAM));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.ACCEPTED));
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "event: message\ndata: " + toolsListResult() + "\n\n", MediaType.TEXT_EVENT_STREAM));

        assertThat(client.listTools(BLSS, "tok-a")).hasSize(1);
    }

    @Test
    void anUnreachableBackendIsReportedUnavailable() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.listTools(BLSS, "tok-a"))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void aToolWithAnInvalidNameIsSkippedWhileItsSiblingsSurvive() {
        expectHandshake();
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
                        + "{\"name\":\"query_asset\",\"inputSchema\":{\"type\":\"object\"}},"
                        + "{\"name\":\"bad.name\",\"inputSchema\":{\"type\":\"object\"}},"
                        + "{\"name\":\"list_alarms\",\"inputSchema\":{\"type\":\"object\"}}]}}",
                MediaType.APPLICATION_JSON));

        List<JsonNode> tools = client.listTools(BLSS, "tok-a");

        assertThat(tools).extracting(t -> t.get("name").asString())
                .containsExactly("query_asset", "list_alarms");
    }

    @Test
    void aToolWithoutAnInputSchemaIsSkippedWhileItsSiblingsSurvive() {
        expectHandshake();
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
                        + "{\"name\":\"query_asset\"},"
                        + "{\"name\":\"list_alarms\",\"inputSchema\":{\"type\":\"object\"}}]}}",
                MediaType.APPLICATION_JSON));

        List<JsonNode> tools = client.listTools(BLSS, "tok-a");

        assertThat(tools).extracting(t -> t.get("name").asString()).containsExactly("list_alarms");
    }

    @Test
    void malformedToolEntriesAreSkippedIndividually() {
        expectHandshake();
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
                        + "\"not-an-object\","
                        + "{\"inputSchema\":{\"type\":\"object\"}},"
                        + "{\"name\":\"\",\"inputSchema\":{\"type\":\"object\"}},"
                        + "{\"name\":\"query_asset\",\"inputSchema\":{\"type\":\"object\"}}]}}",
                MediaType.APPLICATION_JSON));

        assertThat(client.listTools(BLSS, "tok-a"))
                .extracting(t -> t.get("name").asString())
                .containsExactly("query_asset");
    }

    @Test
    void aBackendWhoseEveryToolIsInvalidYieldsAnEmptyButUsableCatalog() {
        expectHandshake();
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"bad.name\"}]}}",
                MediaType.APPLICATION_JSON));

        // Not an exception: the backend answered, it simply contributes nothing to the catalog.
        assertThat(client.listTools(BLSS, "tok-a")).isEmpty();
    }

    @Test
    void aStructurallyUnusableCatalogFailsTheWholeDiscoveryAttempt() {
        expectHandshake();
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":\"oops\"}}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.listTools(BLSS, "tok-a"))
                .isInstanceOf(InvalidBackendCatalogException.class);
    }

    @Test
    void aMissingToolsArrayFailsTheWholeDiscoveryAttempt() {
        expectHandshake();
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.listTools(BLSS, "tok-a"))
                .isInstanceOf(InvalidBackendCatalogException.class);
    }

    @Test
    void aBackendJsonRpcErrorOnAToolCallIsRelayedNotSwallowed() {
        expectHandshake();
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32000,\"message\":\"forbidden\"}}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.callTool(BLSS, "tok-a", "query_asset", null))
                .isInstanceOf(BackendJsonRpcErrorException.class)
                .satisfies(e -> assertThat(((BackendJsonRpcErrorException) e).error().get("message").asString())
                        .isEqualTo("forbidden"));
    }

    @Test
    void theTokenIsMaskedForDiagnostics() {
        assertThat(StreamableHttpBackendMcpClient.maskToken("supersecrettokenvalue"))
                .doesNotContain("supersecrettokenvalue")
                .startsWith("supers");
    }

    // --- expectation helpers ------------------------------------------------------------------

    private void expectHandshake() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(initializeResult(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.ACCEPTED));
    }

    private void expectInitialize(String token, String sessionId) {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.method").value("initialize"))
                .andRespond(withSuccess(initializeResult(), MediaType.APPLICATION_JSON)
                        .header(McpProtocol.SESSION_HEADER, sessionId));
    }

    private void expectInitializedNotification(String token, String sessionId) {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(header(McpProtocol.SESSION_HEADER, sessionId))
                .andExpect(jsonPath("$.method").value("notifications/initialized"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));
    }

    private void expectToolsList(String token, String sessionId) {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(header(McpProtocol.SESSION_HEADER, sessionId))
                .andExpect(jsonPath("$.method").value("tools/list"))
                .andRespond(withSuccess(toolsListResult(), MediaType.APPLICATION_JSON));
    }

    private void expectSessionDelete(String token, String sessionId) {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.DELETE))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(header(McpProtocol.SESSION_HEADER, sessionId))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
    }

    private static String initializeResult() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"" + McpProtocol.VERSION
                + "\",\"capabilities\":{\"tools\":{}},"
                + "\"serverInfo\":{\"name\":\"blss-mcp\",\"version\":\"1.0\"}}}";
    }

    private static String toolsListResult() {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"query_asset\","
                + "\"description\":\"Query an asset\",\"inputSchema\":{\"type\":\"object\"}}]}}";
    }

    private static String callResult() {
        return "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}";
    }
}
