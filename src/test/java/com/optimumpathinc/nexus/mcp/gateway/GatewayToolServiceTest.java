package com.optimumpathinc.nexus.mcp.gateway;

import static com.optimumpathinc.nexus.mcp.gateway.GatewayTestSupport.MAPPER;
import static com.optimumpathinc.nexus.mcp.gateway.GatewayTestSupport.RecordingBackendMcpClient;
import static com.optimumpathinc.nexus.mcp.gateway.GatewayTestSupport.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tasks 7.16-7.17 — prefix routing, de-prefixing, OBO token forwarding, unchanged backend results,
 * and the stable error precedence of {@link GatewayErrorCode}.
 */
class GatewayToolServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final BackendRegistry registry = GatewayTestSupport.registry(
            "blss", "http://blss.local/mcp",
            "superset", "http://superset.local/mcp");

    private GatewayToolService service(RecordingBackendMcpClient client) {
        ToolCatalogService catalog = new ToolCatalogService(
                registry, client, Duration.ofMinutes(10), Duration.ofSeconds(30), clock);
        return new GatewayToolService(registry, catalog, client);
    }

    private static RecordingBackendMcpClient healthyClient() {
        return new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"))
                .withCatalog("superset", tool("run_sql"));
    }

    @Test
    void aPrefixedCallIsRoutedDePrefixedAndCarriesTheValidatedToken() {
        ObjectNode backendResult = MAPPER.createObjectNode();
        backendResult.putArray("content").addObject().put("type", "text").put("text", "asset-42");
        RecordingBackendMcpClient client = healthyClient()
                .withCallResult("blss", "query_asset", backendResult);
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("assetId", 42);

        JsonNode result = service(client).callTool("user-token", "blss__query_asset", arguments);

        assertThat(client.calledNativeNames).containsExactly("query_asset");
        assertThat(client.callTokens).containsExactly("user-token");
        assertThat(client.callArguments).containsExactly(arguments);
        // The backend result is relayed to the Agent unchanged.
        assertThat(result).isEqualTo(backendResult);
    }

    @Test
    void everyBackendRequestCarriesItsOwnCurrentToken() {
        RecordingBackendMcpClient client = healthyClient();
        GatewayToolService service = service(client);

        service.listTools("user-a-token");
        service.callTool("user-b-token", "blss__query_asset", null);

        // Discovery ran as user A, but the tool call is authorized only as user B.
        assertThat(client.discoveryTokens).containsOnly("user-a-token");
        assertThat(client.callTokens).containsExactly("user-b-token");
    }

    @Test
    void aDirectToolCallLazilyBuildsTheCatalogFirst() {
        RecordingBackendMcpClient client = healthyClient();
        GatewayToolService service = service(client);

        service.callTool("user-token", "blss__query_asset", null);

        assertThat(client.discoveries).hasValue(2);
        assertThat(client.calledNativeNames).containsExactly("query_asset");
    }

    @Test
    void anUnconfiguredPrefixIsRejectedBeforeAnyDiscovery() {
        RecordingBackendMcpClient client = healthyClient();
        GatewayToolService service = service(client);

        assertThat(categoryOf(() -> service.callTool("token", "unknown__do_thing", null)))
                .isEqualTo(GatewayErrorCode.UNKNOWN_PREFIX);
        assertThat(client.discoveries).hasValue(0);
        assertThat(client.calledNativeNames).isEmpty();
    }

    @Test
    void anUnprefixedToolNameIsRejectedAsAnUnknownPrefix() {
        GatewayToolService service = service(healthyClient());

        assertThat(categoryOf(() -> service.callTool("token", "query_asset", null)))
                .isEqualTo(GatewayErrorCode.UNKNOWN_PREFIX);
    }

    @Test
    void aConfiguredButUndiscoveredBackendIsReportedUnavailable() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"))
                .withDiscoveryFailure("superset", new BackendUnavailableException("down"));
        GatewayToolService service = service(client);

        assertThat(categoryOf(() -> service.callTool("token", "superset__run_sql", null)))
                .isEqualTo(GatewayErrorCode.BACKEND_UNAVAILABLE);
    }

    @Test
    void aBackendWithAnInvalidCatalogIsReportedDistinctly() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"))
                .withDiscoveryFailure("superset", new InvalidBackendCatalogException("no tools array"));
        GatewayToolService service = service(client);

        assertThat(categoryOf(() -> service.callTool("token", "superset__run_sql", null)))
                .isEqualTo(GatewayErrorCode.INVALID_BACKEND_CATALOG);
    }

    @Test
    void aDiscoveredBackendWithoutTheNativeToolYieldsUnknownTool() {
        GatewayToolService service = service(healthyClient());

        assertThat(categoryOf(() -> service.callTool("token", "blss__no_such_tool", null)))
                .isEqualTo(GatewayErrorCode.UNKNOWN_TOOL);
    }

    @Test
    void aToolSkippedAsInvalidDuringDiscoveryIsReportedAsUnknownTool() {
        // The client dropped 'run_sql' as an invalid definition, so superset discovered successfully
        // but contributes nothing. Calling it must be unknown_tool, not backend_unavailable.
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"))
                .withCatalog("superset");
        GatewayToolService service = service(client);

        assertThat(categoryOf(() -> service.callTool("token", "superset__run_sql", null)))
                .isEqualTo(GatewayErrorCode.UNKNOWN_TOOL);
        assertThat(client.calledNativeNames).isEmpty();
    }

    @Test
    void totalDiscoveryFailureYieldsCatalogUnavailable() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withDiscoveryFailure("blss", new BackendUnavailableException("down"))
                .withDiscoveryFailure("superset", new BackendUnavailableException("down"));
        GatewayToolService service = service(client);

        assertThat(categoryOf(() -> service.callTool("token", "blss__query_asset", null)))
                .isEqualTo(GatewayErrorCode.CATALOG_UNAVAILABLE);
        assertThat(categoryOf(() -> service.listTools("token")))
                .isEqualTo(GatewayErrorCode.CATALOG_UNAVAILABLE);
    }

    @Test
    void aCallToADownBackendFailsOnlyThatCall() {
        RecordingBackendMcpClient client = healthyClient()
                .withCallFailure("superset", "run_sql", new BackendUnavailableException("down"));
        GatewayToolService service = service(client);

        assertThat(categoryOf(() -> service.callTool("token", "superset__run_sql", null)))
                .isEqualTo(GatewayErrorCode.BACKEND_UNAVAILABLE);
        // A tool on a reachable backend still succeeds.
        assertThat(service.callTool("token", "blss__query_asset", null)).isNotNull();
    }

    @Test
    void aBackendBusinessErrorIsRelayedUnchanged() {
        ObjectNode backendError = MAPPER.createObjectNode();
        backendError.put("code", -32000).put("message", "user is not permitted to read asset 42");
        RecordingBackendMcpClient client = healthyClient()
                .withCallFailure("blss", "query_asset", new BackendJsonRpcErrorException(backendError));
        GatewayToolService service = service(client);

        assertThatThrownBy(() -> service.callTool("token", "blss__query_asset", null))
                .isInstanceOf(BackendJsonRpcErrorException.class)
                .satisfies(e -> assertThat(((BackendJsonRpcErrorException) e).error()).isEqualTo(backendError));
    }

    @Test
    void gatewayErrorMessagesNeverLeakTokensOrBackendUrls() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withDiscoveryFailure("blss",
                        new BackendUnavailableException("connect to http://blss.local/mcp refused"))
                .withDiscoveryFailure("superset",
                        new BackendUnavailableException("connect to http://superset.local/mcp refused"));
        GatewayToolService service = service(client);

        assertThatThrownBy(() -> service.callTool("secret-token", "blss__query_asset", null))
                .isInstanceOf(GatewayMcpException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("secret-token")
                        .doesNotContain("blss.local"));
    }

    /** Asserts the failure is a gateway failure and returns its stable category. */
    private static GatewayErrorCode categoryOf(ThrowingCallable callable) {
        Throwable thrown = catchThrowable(callable);
        assertThat(thrown).isInstanceOf(GatewayMcpException.class);
        return ((GatewayMcpException) thrown).errorCode();
    }
}
