package com.optimumpathinc.nexus.mcp.gateway;

/**
 * A gateway-originated MCP failure carrying one of the stable categories in {@link GatewayErrorCode}
 * (D15). The message is always the category's safe message so no token or internal backend URL can
 * leak to the Agent.
 */
public class GatewayMcpException extends RuntimeException {

    private final GatewayErrorCode errorCode;

    public GatewayMcpException(GatewayErrorCode errorCode) {
        super(errorCode.safeMessage());
        this.errorCode = errorCode;
    }

    public GatewayErrorCode errorCode() {
        return errorCode;
    }
}
