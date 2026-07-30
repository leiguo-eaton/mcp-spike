package com.optimumpathinc.nexus.mcp.tools;

import com.optimumpathinc.nexus.mcp.master.MasterClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Read-only MCP tools backed by BLSS master REST APIs.
 *
 * <p>POC scope: a single {@code get_topology} tool. All calls run on-behalf-of the end
 * user via the delegation token (see {@link MasterClient}).
 */
@Service
public class TopologyTools {

    private final MasterClient master;

    public TopologyTools(MasterClient master) {
        this.master = master;
    }

    @Tool(name = "get_topology",
            description = "Return the device hierarchy/topology visible to the current user from BLSS master.")
    public String getTopology(
            @ToolParam(required = false, description = "Optional parent node id to scope the topology")
            String parentId) {
        String path = (parentId == null || parentId.isBlank())
                ? "/hierarchy"
                : "/hierarchy?parentId=" + parentId;
        return master.get(path);
    }
}
