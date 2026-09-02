package com.optimumpathinc.nexus.mcp.gateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gateway's MCP server endpoint (stateless Streamable HTTP).
 *
 * <p>Only the {@code tools} capability is advertised and served (D12). {@code resources},
 * {@code prompts}, {@code logging} and {@code completions} are neither advertised nor forwarded to
 * any backend — they are answered with a JSON-RPC "method not found".
 *
 * <p>The gateway's own {@code initialize} handshake does not need the downstream catalog and
 * therefore never triggers backend discovery (D10). Discovery happens only inside
 * {@code tools/list} / {@code tools/call}, using the validated token of the triggering request.
 */
@RestController
public class McpGatewayController {

    private static final Logger log = LoggerFactory.getLogger(McpGatewayController.class);

    private final GatewayToolService tools;
    private final ObjectMapper mapper;

    public McpGatewayController(GatewayToolService tools, ObjectMapper mapper) {
        this.tools = tools;
        this.mapper = mapper;
    }

    @PostMapping(path = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> handle(@RequestBody JsonNode message) {
        if (message == null || !message.isObject()) {
            return ResponseEntity.badRequest().body(
                    JsonRpc.error(mapper, null, JsonRpc.INVALID_REQUEST, "Invalid JSON-RPC request", null));
        }
        JsonNode id = message.get("id");
        String method = message.path("method").asString(null);
        if (id == null || id.isNull()) {
            // A notification (e.g. notifications/initialized) is acknowledged without a body.
            return ResponseEntity.accepted().build();
        }
        if (method == null || method.isBlank()) {
            return ResponseEntity.ok(
                    JsonRpc.error(mapper, id, JsonRpc.INVALID_REQUEST, "Missing JSON-RPC method", null));
        }
        try {
            return ResponseEntity.ok(JsonRpc.success(mapper, id, dispatch(method, message.path("params"))));
        } catch (GatewayMcpException e) {
            return ResponseEntity.ok(JsonRpc.gatewayError(mapper, id, e.errorCode()));
        } catch (BackendJsonRpcErrorException e) {
            // Relay the backend's own failure (e.g. business authorization) unchanged.
            return ResponseEntity.ok(JsonRpc.errorEnvelope(mapper, id, e.error()));
        } catch (UnsupportedMcpMethodException e) {
            return ResponseEntity.ok(
                    JsonRpc.error(mapper, id, JsonRpc.METHOD_NOT_FOUND, "Method not found: " + method, null));
        } catch (RuntimeException e) {
            log.warn("MCP request '{}' failed: {}", method, e.getMessage());
            return ResponseEntity.ok(
                    JsonRpc.error(mapper, id, JsonRpc.INTERNAL_ERROR, "Internal gateway error", null));
        }
    }

    /** Streamable HTTP server-initiated stream. The gateway pushes nothing, so no stream is opened. */
    @GetMapping("/mcp")
    public ResponseEntity<Void> stream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    /** Session termination. The gateway is stateless, so there is never session state to discard. */
    @DeleteMapping("/mcp")
    public ResponseEntity<Void> terminate() {
        return ResponseEntity.noContent().build();
    }

    private JsonNode dispatch(String method, JsonNode params) {
        return switch (method) {
            case "initialize" -> initializeResult(params);
            case "ping" -> mapper.createObjectNode();
            case "tools/list" -> toolsList();
            case "tools/call" -> toolsCall(params);
            default -> throw new UnsupportedMcpMethodException(method);
        };
    }

    /** Advertises only {@code tools} (D12). Requires no downstream catalog, so triggers no discovery. */
    private JsonNode initializeResult(JsonNode params) {
        String requested = params.path("protocolVersion").asString(null);
        String negotiated = requested != null && McpProtocol.SUPPORTED_VERSIONS.contains(requested)
                ? requested
                : McpProtocol.VERSION;

        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", negotiated);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", McpProtocol.SERVER_NAME);
        serverInfo.put("version", McpProtocol.SERVER_VERSION);
        return result;
    }

    private JsonNode toolsList() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode array = result.putArray("tools");
        tools.listTools(currentAccessToken()).forEach(array::add);
        return result;
    }

    private JsonNode toolsCall(JsonNode params) {
        String name = params.path("name").asString(null);
        JsonNode arguments = params.get("arguments");
        return tools.callTool(currentAccessToken(), name, arguments);
    }

    /**
     * The raw {@code MCP_ACCESS_TOKEN} the resource server just validated for this request. It is
     * read per request and passed down as a parameter, never cached.
     */
    private String currentAccessToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getTokenValue();
        }
        // Unreachable through the secured chain; never substitute a shared credential.
        throw new IllegalStateException("No validated delegated access token on the current request");
    }

    /** Signals an MCP method the gateway deliberately does not expose or route. */
    static final class UnsupportedMcpMethodException extends RuntimeException {
        UnsupportedMcpMethodException(String method) {
            super("Unsupported MCP method: " + method);
        }
    }
}
