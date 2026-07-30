package com.optimumpathinc.nexus.mcp.config;

import com.optimumpathinc.nexus.mcp.tools.DeviceSearchTools;
import com.optimumpathinc.nexus.mcp.tools.TopologyTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolsConfig {

    /** Exposes the @Tool-annotated methods to the MCP server. */
    @Bean
    ToolCallbackProvider blssToolCallbacks(TopologyTools topologyTools, DeviceSearchTools deviceSearchTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(topologyTools, deviceSearchTools)
                .build();
    }
}
