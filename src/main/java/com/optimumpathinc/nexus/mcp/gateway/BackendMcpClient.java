package com.optimumpathinc.nexus.mcp.gateway;

import tools.jackson.databind.JsonNode;
import java.util.List;

/**
 * The gateway's MCP <em>client</em> side: it talks MCP to each configured backend server.
 *
 * <p>Every method receives the current request's validated {@code MCP_ACCESS_TOKEN} and MUST send it
 * as {@code Authorization: Bearer <token>} on every outbound HTTP request it makes, including
 * {@code initialize}, {@code tools/list}, {@code tools/call}, and session termination (D7). The
 * token is a parameter only — implementations MUST NOT retain it.
 */
public interface BackendMcpClient {

    /**
     * Discovers a backend's tools. Uses a temporary MCP session when the backend requires one, and
     * closes it before returning.
     *
     * @return the backend-native tool definitions, exactly as returned by the backend, minus any
     *         individual definition that was invalid and therefore skipped (D14)
     * @throws InvalidBackendCatalogException when the response has no readable {@code tools} array
     * @throws BackendUnavailableException    when the backend cannot be reached or errors
     */
    List<JsonNode> listTools(BackendDefinition backend, String accessToken);

    /**
     * Executes a backend-native tool on behalf of the token's subject.
     *
     * @param nativeToolName the de-prefixed, backend-native tool name
     * @param arguments      the tool arguments, forwarded unchanged (may be {@code null})
     * @return the backend's {@code tools/call} result, unchanged
     * @throws BackendJsonRpcErrorException when the backend answers with a JSON-RPC error
     * @throws BackendUnavailableException  when the backend cannot be reached
     */
    JsonNode callTool(BackendDefinition backend, String accessToken, String nativeToolName, JsonNode arguments);
}
