package com.optimumpathinc.nexus.mcp.gateway;

/**
 * Stable gateway error categories (D15).
 *
 * <p>The JSON-RPC numeric code may follow the transport/SDK convention, but the {@code category}
 * string and the safe message are part of the gateway contract. No message ever contains a token or
 * an internal backend URL.
 */
public enum GatewayErrorCode {

    /** The tool name's prefix matches no configured backend. Rejected before catalog discovery. */
    UNKNOWN_PREFIX("unknown_prefix", -32602, "Unknown tool namespace prefix"),

    /** The prefix names a configured backend whose catalog could not be discovered. */
    BACKEND_UNAVAILABLE("backend_unavailable", -32003, "Backend MCP server is unavailable"),

    /** The backend was discovered, but does not expose the de-prefixed tool. */
    UNKNOWN_TOOL("unknown_tool", -32602, "Unknown tool"),

    /** The backend's {@code tools/list} response was structurally unusable (no readable array). */
    INVALID_BACKEND_CATALOG("invalid_backend_catalog", -32004,
            "Backend MCP server returned an invalid tool catalog"),

    /** No catalog exists because every configured backend failed initial discovery. */
    CATALOG_UNAVAILABLE("catalog_unavailable", -32002, "Tool catalog is currently unavailable");

    private final String category;
    private final int jsonRpcCode;
    private final String safeMessage;

    GatewayErrorCode(String category, int jsonRpcCode, String safeMessage) {
        this.category = category;
        this.jsonRpcCode = jsonRpcCode;
        this.safeMessage = safeMessage;
    }

    public String category() {
        return category;
    }

    public int jsonRpcCode() {
        return jsonRpcCode;
    }

    public String safeMessage() {
        return safeMessage;
    }
}
