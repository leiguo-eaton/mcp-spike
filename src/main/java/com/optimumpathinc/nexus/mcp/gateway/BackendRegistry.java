package com.optimumpathinc.nexus.mcp.gateway;

import com.optimumpathinc.nexus.mcp.config.SidecarProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The static registry of backend MCP servers the gateway aggregates (D13).
 *
 * <p>Validation happens at construction time, so an invalid registry fails gateway startup. A valid
 * entry whose backend is unreachable does NOT fail startup: discovery is lazy (D10) and no backend
 * is contacted here.
 */
@Component
public class BackendRegistry {

    /** MCP tool-name grammar; the prefix must be usable inside an MCP tool name. */
    private static final Pattern PREFIX_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+");

    private final List<BackendDefinition> backends;
    private final Map<String, BackendDefinition> byPrefix;

    @Autowired
    public BackendRegistry(SidecarProperties props) {
        this(props.getBackends());
    }

    /** Test seam: build the registry straight from a backend list. */
    public BackendRegistry(List<SidecarProperties.Backend> configured) {
        List<BackendDefinition> validated = new ArrayList<>();
        Map<String, BackendDefinition> index = new LinkedHashMap<>();
        for (SidecarProperties.Backend backend : configured == null ? List.<SidecarProperties.Backend>of() : configured) {
            BackendDefinition definition = validate(backend);
            if (index.putIfAbsent(definition.prefix(), definition) != null) {
                throw new IllegalStateException(
                        "Invalid sidecar.backends: duplicate prefix '" + definition.prefix() + "'");
            }
            validated.add(definition);
        }
        this.backends = List.copyOf(validated);
        this.byPrefix = Map.copyOf(index);
    }

    private static BackendDefinition validate(SidecarProperties.Backend backend) {
        String prefix = backend.getPrefix();
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException("Invalid sidecar.backends: prefix is required");
        }
        if (!PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new IllegalStateException(
                    "Invalid sidecar.backends: prefix '" + prefix + "' must match [a-zA-Z0-9_-]+");
        }
        if (prefix.contains(BackendDefinition.SEPARATOR)) {
            throw new IllegalStateException(
                    "Invalid sidecar.backends: prefix '" + prefix + "' must not contain '"
                            + BackendDefinition.SEPARATOR + "'");
        }
        String url = backend.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "Invalid sidecar.backends: url is required for prefix '" + prefix + "'");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(
                    "Invalid sidecar.backends: url '" + url + "' for prefix '" + prefix + "' is not a valid URI", e);
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalStateException(
                    "Invalid sidecar.backends: url '" + url + "' for prefix '" + prefix + "' must be absolute");
        }
        return new BackendDefinition(prefix, uri);
    }

    /** All configured backends, in configuration order. */
    public List<BackendDefinition> backends() {
        return backends;
    }

    /** Resolves a configured backend by its namespace prefix, or {@code null} when unconfigured. */
    public BackendDefinition byPrefix(String prefix) {
        return byPrefix.get(prefix);
    }
}
