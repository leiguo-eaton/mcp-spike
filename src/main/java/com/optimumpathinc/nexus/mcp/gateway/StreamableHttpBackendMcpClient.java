package com.optimumpathinc.nexus.mcp.gateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.optimumpathinc.nexus.mcp.config.SidecarProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Streamable HTTP MCP client used by the gateway to talk to backend MCP servers.
 *
 * <p>On-Behalf-Of (D4/D7): every outbound request — {@code initialize}, {@code notifications/
 * initialized}, {@code tools/list}, {@code tools/call} and session termination — carries the
 * <em>current</em> request's validated {@code MCP_ACCESS_TOKEN} as {@code Authorization: Bearer}.
 * The gateway holds no service credential and never falls back to a shared one. The token is only
 * ever a method parameter, so it is discarded when the operation completes.
 *
 * <p>Sessions: operation is stateless whenever the backend allows it. If the backend answers
 * {@code initialize} with an {@code Mcp-Session-Id}, that session is temporary — it is used for this
 * one operation and deleted in a {@code finally} block, so an identity-bound session is never shared
 * across users.
 */
@Component
public class StreamableHttpBackendMcpClient implements BackendMcpClient {

    private static final Logger log = LoggerFactory.getLogger(StreamableHttpBackendMcpClient.class);

    /** Backend-native tool names must be valid MCP tool names. */
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+");

    private static final String ACCEPT_TYPES = "application/json, text/event-stream";

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final AtomicLong requestIds = new AtomicLong();

    @Autowired
    public StreamableHttpBackendMcpClient(SidecarProperties props, ObjectMapper mapper) {
        this(buildRestClient(props), mapper);
    }

    /** Test seam: inject a {@link RestClient} bound to a mock server. */
    public StreamableHttpBackendMcpClient(RestClient restClient, ObjectMapper mapper) {
        this.restClient = restClient;
        this.mapper = mapper;
    }

    private static RestClient buildRestClient(SidecarProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getBackendConnectTimeout());
        factory.setReadTimeout(props.getBackendReadTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public List<JsonNode> listTools(BackendDefinition backend, String accessToken) {
        log.debug("MCP discovery on backend '{}' | token={}", backend.prefix(), maskToken(accessToken));
        Session session = new Session(backend, accessToken);
        try {
            session.initialize();
            JsonNode result = session.call("tools/list", mapper.createObjectNode());
            return toolDefinitions(backend, result);
        } catch (BackendJsonRpcErrorException e) {
            // A protocol-level error during discovery means this backend's catalog is unusable.
            throw new BackendUnavailableException(
                    "Backend '" + backend.prefix() + "' rejected tool discovery", e);
        } finally {
            session.close();
        }
    }

    @Override
    public JsonNode callTool(BackendDefinition backend, String accessToken, String nativeToolName,
            JsonNode arguments) {
        log.debug("MCP tools/call '{}' on backend '{}' | token={}", nativeToolName, backend.prefix(),
                maskToken(accessToken));
        Session session = new Session(backend, accessToken);
        try {
            session.initialize();
            ObjectNode params = mapper.createObjectNode();
            params.put("name", nativeToolName);
            params.set("arguments", arguments == null || arguments.isNull()
                    ? mapper.createObjectNode()
                    : arguments);
            return session.call("tools/call", params);
        } finally {
            session.close();
        }
    }

    /**
     * Copies the backend-native tool definitions, skipping individual invalid ones (D14).
     *
     * <p>An invalid definition is dropped and logged rather than failing the whole backend, so one
     * malformed tool cannot take an entire namespace offline. The backend's discovery fails as a
     * unit only when the response is structurally unusable — no {@code tools} array to read.
     */
    private List<JsonNode> toolDefinitions(BackendDefinition backend, JsonNode result) {
        JsonNode tools = result.get("tools");
        if (tools == null || !tools.isArray()) {
            throw new InvalidBackendCatalogException(
                    "Backend '" + backend.prefix() + "' returned no tools array");
        }
        List<JsonNode> definitions = new ArrayList<>();
        int skipped = 0;
        for (JsonNode tool : tools) {
            String rejection = rejectionReason(tool);
            if (rejection != null) {
                skipped++;
                log.warn("Backend '{}': skipping tool {} — {}", backend.prefix(), describe(tool), rejection);
                continue;
            }
            definitions.add(tool.deepCopy());
        }
        if (skipped > 0) {
            log.warn("Backend '{}': published {} tool(s), skipped {} invalid definition(s)",
                    backend.prefix(), definitions.size(), skipped);
        }
        return List.copyOf(definitions);
    }

    /** Returns why this tool definition is unusable, or {@code null} when it is valid. */
    private static String rejectionReason(JsonNode tool) {
        if (tool == null || !tool.isObject()) {
            return "definition is not a JSON object";
        }
        JsonNode name = tool.get("name");
        if (name == null || !name.isTextual() || name.asString().isBlank()) {
            return "missing or non-textual name";
        }
        if (!TOOL_NAME_PATTERN.matcher(name.asString()).matches()) {
            return "name is outside the MCP tool-name grammar [a-zA-Z0-9_-]+";
        }
        JsonNode inputSchema = tool.get("inputSchema");
        if (inputSchema == null || !inputSchema.isObject()) {
            return "missing or non-object inputSchema";
        }
        return null;
    }

    /** A safe identifier for a rejected tool, for logging only. */
    private static String describe(JsonNode tool) {
        if (tool != null && tool.isObject()) {
            JsonNode name = tool.get("name");
            if (name != null && name.isTextual() && !name.asString().isBlank()) {
                return "'" + name.asString() + "'";
            }
        }
        return "<unnamed>";
    }

    /** Masks the delegated token so logs never contain the full secret. */
    static String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "<none>";
        }
        return token.substring(0, Math.min(6, token.length())) + "…(len=" + token.length() + ")";
    }

    /**
     * One temporary MCP conversation with a backend. Closing it deletes the backend session when the
     * backend created one; a stateless backend needs no cleanup.
     */
    private final class Session implements AutoCloseable {

        private final BackendDefinition backend;
        private final String accessToken;
        private String sessionId;

        private Session(BackendDefinition backend, String accessToken) {
            this.backend = backend;
            this.accessToken = accessToken;
        }

        private void initialize() {
            ObjectNode params = mapper.createObjectNode();
            params.put("protocolVersion", McpProtocol.VERSION);
            params.set("capabilities", mapper.createObjectNode());
            ObjectNode clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", McpProtocol.SERVER_NAME);
            clientInfo.put("version", McpProtocol.SERVER_VERSION);

            ResponseEntity<String> response =
                    post(JsonRpc.request(mapper, requestIds.incrementAndGet(), "initialize", params));
            this.sessionId = response.getHeaders().getFirst(McpProtocol.SESSION_HEADER);
            resultOf(parse(response));
            post(JsonRpc.notification(mapper, "notifications/initialized", null));
        }

        private JsonNode call(String method, JsonNode params) {
            ResponseEntity<String> response =
                    post(JsonRpc.request(mapper, requestIds.incrementAndGet(), method, params));
            return resultOf(parse(response));
        }

        private ResponseEntity<String> post(JsonNode payload) {
            try {
                return restClient.post()
                        .uri(backend.baseUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT, ACCEPT_TYPES)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .header(McpProtocol.PROTOCOL_VERSION_HEADER, McpProtocol.VERSION)
                        .headers(headers -> {
                            if (sessionId != null) {
                                headers.set(McpProtocol.SESSION_HEADER, sessionId);
                            }
                        })
                        .body(payload)
                        .retrieve()
                        .toEntity(String.class);
            } catch (RestClientException e) {
                throw new BackendUnavailableException(
                        "Backend '" + backend.prefix() + "' MCP request failed", e);
            }
        }

        @Override
        public void close() {
            if (sessionId == null) {
                return;
            }
            try {
                restClient.delete()
                        .uri(backend.baseUrl())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .header(McpProtocol.PROTOCOL_VERSION_HEADER, McpProtocol.VERSION)
                        .header(McpProtocol.SESSION_HEADER, sessionId)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientException e) {
                // Session cleanup is best-effort; the backend expires it on its own.
                log.debug("Backend '{}' session termination failed: {}", backend.prefix(), e.getMessage());
            } finally {
                sessionId = null;
            }
        }

        private JsonNode parse(ResponseEntity<String> response) {
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                throw new BackendUnavailableException(
                        "Backend '" + backend.prefix() + "' returned an empty MCP response");
            }
            MediaType contentType = response.getHeaders().getContentType();
            boolean sse = (contentType != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType))
                    || body.stripLeading().startsWith("event:")
                    || body.stripLeading().startsWith("data:");
            String json = sse ? firstSseData(body) : body.trim();
            try {
                return mapper.readTree(json);
            } catch (Exception e) {
                throw new BackendUnavailableException(
                        "Backend '" + backend.prefix() + "' returned an unreadable MCP response", e);
            }
        }

        private String firstSseData(String body) {
            StringBuilder data = new StringBuilder();
            for (String line : body.split("\\R")) {
                if (line.startsWith("data:")) {
                    data.append(line.substring(5).stripLeading());
                } else if (!data.isEmpty() && line.isBlank()) {
                    break;
                }
            }
            if (data.isEmpty()) {
                throw new BackendUnavailableException(
                        "Backend '" + backend.prefix() + "' returned an SSE stream without data");
            }
            return data.toString();
        }

        private JsonNode resultOf(JsonNode message) {
            JsonNode error = message.get("error");
            if (error != null && !error.isNull()) {
                throw new BackendJsonRpcErrorException(error.deepCopy());
            }
            JsonNode result = message.get("result");
            if (result == null || result.isNull()) {
                throw new BackendUnavailableException(
                        "Backend '" + backend.prefix() + "' returned no MCP result");
            }
            return result;
        }
    }
}
