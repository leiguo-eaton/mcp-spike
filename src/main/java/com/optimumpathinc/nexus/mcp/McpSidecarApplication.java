package com.optimumpathinc.nexus.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpSidecarApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpSidecarApplication.class, args);
    }
}
