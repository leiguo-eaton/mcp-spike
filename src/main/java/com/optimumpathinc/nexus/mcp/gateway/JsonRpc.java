package com.optimumpathinc.nexus.mcp.gateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Minimal JSON-RPC 2.0 message helpers shared by the gateway server and backend client. */
public final class JsonRpc {

    public static final String VERSION = "2.0";

    /** JSON-RPC "method not found" — used for capabilities the gateway does not expose (D12). */
    public static final int METHOD_NOT_FOUND = -32601;

    /** JSON-RPC "invalid request". */
    public static final int INVALID_REQUEST = -32600;

    /** JSON-RPC "internal error". */
    public static final int INTERNAL_ERROR = -32603;

    private JsonRpc() {
    }

    public static ObjectNode request(ObjectMapper mapper, long id, String method, JsonNode params) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", VERSION);
        node.put("id", id);
        node.put("method", method);
        if (params != null) {
            node.set("params", params);
        }
        return node;
    }

    public static ObjectNode notification(ObjectMapper mapper, String method, JsonNode params) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", VERSION);
        node.put("method", method);
        if (params != null) {
            node.set("params", params);
        }
        return node;
    }

    public static ObjectNode success(ObjectMapper mapper, JsonNode id, JsonNode result) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", VERSION);
        node.set("id", id == null ? mapper.nullNode() : id);
        node.set("result", result);
        return node;
    }

    public static ObjectNode error(ObjectMapper mapper, JsonNode id, int code, String message, JsonNode data) {
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.set("data", data);
        }
        return errorEnvelope(mapper, id, error);
    }

    /** Wraps an already-built JSON-RPC {@code error} object (e.g. relayed from a backend). */
    public static ObjectNode errorEnvelope(ObjectMapper mapper, JsonNode id, JsonNode error) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", VERSION);
        node.set("id", id == null ? mapper.nullNode() : id);
        node.set("error", error);
        return node;
    }

    /** Builds the gateway error body for a stable {@link GatewayErrorCode} category (D15). */
    public static ObjectNode gatewayError(ObjectMapper mapper, JsonNode id, GatewayErrorCode code) {
        ObjectNode data = mapper.createObjectNode();
        data.put("category", code.category());
        return error(mapper, id, code.jsonRpcCode(), code.safeMessage(), data);
    }
}
