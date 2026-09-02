package com.optimumpathinc.nexus.mcp.gateway;

import java.util.List;

/** Protocol constants shared by the gateway's MCP server side and its backend MCP client side. */
public final class McpProtocol {

    /** MCP protocol revision the gateway speaks by default. */
    public static final String VERSION = "2025-06-18";

    /** Protocol revisions the gateway will accept during {@code initialize}. */
    public static final List<String> SUPPORTED_VERSIONS = List.of("2025-06-18", "2025-03-26");

    /** Streamable HTTP session header. Carries protocol state only, never identity (D7). */
    public static final String SESSION_HEADER = "Mcp-Session-Id";

    /** Streamable HTTP protocol-version header. */
    public static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

    public static final String SERVER_NAME = "blss-mcp-gateway";

    public static final String SERVER_VERSION = "0.1.0";

    private McpProtocol() {
    }
}
