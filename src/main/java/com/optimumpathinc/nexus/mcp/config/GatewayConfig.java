package com.optimumpathinc.nexus.mcp.config;

import com.optimumpathinc.nexus.mcp.gateway.BackendMcpClient;
import com.optimumpathinc.nexus.mcp.gateway.BackendRegistry;
import com.optimumpathinc.nexus.mcp.gateway.ToolCatalogService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the MCP gateway's aggregation layer. No backend is contacted here — discovery is lazy. */
@Configuration
public class GatewayConfig {

    @Bean
    Clock gatewayClock() {
        return Clock.systemUTC();
    }

    @Bean
    ToolCatalogService toolCatalogService(BackendRegistry registry, BackendMcpClient client,
            SidecarProperties props, Clock gatewayClock) {
        return new ToolCatalogService(registry, client, props.getCatalogTtl(),
                props.getCatalogFailureBackoff(), gatewayClock);
    }
}
