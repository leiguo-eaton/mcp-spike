package com.optimumpathinc.nexus.mcp.gateway;

import com.optimumpathinc.nexus.mcp.config.SidecarProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shared helpers for the gateway aggregation tests. */
final class GatewayTestSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private GatewayTestSupport() {
    }

    static BackendRegistry registry(String... prefixAndUrlPairs) {
        List<SidecarProperties.Backend> backends = new ArrayList<>();
        for (int i = 0; i < prefixAndUrlPairs.length; i += 2) {
            backends.add(new SidecarProperties.Backend(prefixAndUrlPairs[i], prefixAndUrlPairs[i + 1]));
        }
        return new BackendRegistry(backends);
    }

    /** A minimal but valid MCP tool definition. */
    static ObjectNode tool(String name) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.putObject("inputSchema").put("type", "object");
        return tool;
    }

    /** A fully populated tool definition, used to prove non-name fields survive aggregation. */
    static ObjectNode richTool(String name) {
        ObjectNode tool = tool(name);
        tool.put("description", "Describes " + name);
        tool.put("title", "Title of " + name);
        tool.putObject("outputSchema").put("type", "object");
        tool.putObject("annotations").put("readOnlyHint", true);
        tool.put("x-vendor-extension", "kept");
        return tool;
    }

    /**
     * A scripted {@link BackendMcpClient} that records every call (including the token it was given)
     * so tests can assert per-request On-Behalf-Of forwarding.
     */
    static final class RecordingBackendMcpClient implements BackendMcpClient {

        private final Map<String, List<JsonNode>> catalogs = new LinkedHashMap<>();
        private final Map<String, RuntimeException> failures = new LinkedHashMap<>();
        private final Map<String, JsonNode> callResults = new LinkedHashMap<>();
        private final Map<String, RuntimeException> callFailures = new LinkedHashMap<>();

        final List<String> discoveryTokens = new ArrayList<>();
        final List<String> callTokens = new ArrayList<>();
        final List<String> calledNativeNames = new ArrayList<>();
        final List<JsonNode> callArguments = new ArrayList<>();
        final AtomicInteger discoveries = new AtomicInteger();

        private volatile Runnable beforeDiscovery = () -> {
        };

        RecordingBackendMcpClient withCatalog(String prefix, JsonNode... tools) {
            catalogs.put(prefix, List.of(tools));
            failures.remove(prefix);
            return this;
        }

        RecordingBackendMcpClient withDiscoveryFailure(String prefix, RuntimeException failure) {
            failures.put(prefix, failure);
            catalogs.remove(prefix);
            return this;
        }

        RecordingBackendMcpClient withCallResult(String prefix, String nativeName, JsonNode result) {
            callResults.put(prefix + "/" + nativeName, result);
            return this;
        }

        RecordingBackendMcpClient withCallFailure(String prefix, String nativeName, RuntimeException failure) {
            callFailures.put(prefix + "/" + nativeName, failure);
            return this;
        }

        RecordingBackendMcpClient beforeDiscovery(Runnable hook) {
            this.beforeDiscovery = hook;
            return this;
        }

        @Override
        public List<JsonNode> listTools(BackendDefinition backend, String accessToken) {
            beforeDiscovery.run();
            discoveries.incrementAndGet();
            discoveryTokens.add(accessToken);
            RuntimeException failure = failures.get(backend.prefix());
            if (failure != null) {
                throw failure;
            }
            List<JsonNode> tools = catalogs.get(backend.prefix());
            if (tools == null) {
                throw new BackendUnavailableException("no scripted catalog for " + backend.prefix());
            }
            return tools;
        }

        @Override
        public JsonNode callTool(BackendDefinition backend, String accessToken, String nativeToolName,
                JsonNode arguments) {
            callTokens.add(accessToken);
            calledNativeNames.add(nativeToolName);
            callArguments.add(arguments);
            String key = backend.prefix() + "/" + nativeToolName;
            RuntimeException failure = callFailures.get(key);
            if (failure != null) {
                throw failure;
            }
            JsonNode result = callResults.get(key);
            return result == null ? MAPPER.createObjectNode() : result;
        }
    }

    /** Extracts the exposed tool names from a snapshot, in published order. */
    static List<String> toolNames(List<JsonNode> tools) {
        return tools.stream().map(t -> t.get("name").asString()).toList();
    }
}
