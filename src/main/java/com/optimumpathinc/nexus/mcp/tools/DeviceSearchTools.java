package com.optimumpathinc.nexus.mcp.tools;

import com.optimumpathinc.nexus.mcp.master.MasterClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Device-search MCP tools backed by the BLSS master asset search API.
 *
 * <p>All calls run on-behalf-of the end user via the delegation token (see
 * {@link MasterClient}), so results are already scoped by the user's RBAC / device-scope.
 */
@Service
public class DeviceSearchTools {

    private final MasterClient master;

    public DeviceSearchTools(MasterClient master) {
        this.master = master;
    }

    @Tool(name = "search_devices",
            description = """
                    Search BLSS devices by any combination of fuzzy (partial) fields and
                    return the matching devices as raw JSON. Supported filters: device name,
                    device type, vendor, product line (pline), and IPv4 address. Every provided
                    filter is matched as a case-insensitive substring (wildcards added
                    automatically) and multiple filters are combined with AND. At least one
                    filter should be provided. Results are limited to devices the current user
                    can see.
                    
                    Response schema: JSON object { code, data: { count, list: [ device, ... ] } }
                    where each device includes fields such as: id, name, description,
                    type_name, vendor_name, model_name, pline_name, ipv4_address (array),
                    mac_address (array), serial_number, firmware_version, life_cycle, monitored,
                    alarm (count), status_id, up_time, dgroup_name (array), and ports (array).
                    count is the number of matches; list is empty when nothing matches.
                    """)
    public String searchDevices(
            @ToolParam(required = false, description = "Fuzzy device name, e.g. 'cam' or 'ups'. "
                    + "Pass the plain substring only.")
            String name,
            @ToolParam(required = false, description = "Fuzzy device type / type_name, e.g. 'Sensor' or 'UPS'.")
            String type,
            @ToolParam(required = false, description = "Fuzzy vendor / vendor_name, e.g. 'Eaton'.")
            String vendorName,
            @ToolParam(required = false, description = "Fuzzy product line / pline_name, e.g. 'Env'.")
            String plineName,
            @ToolParam(required = false, description = "Fuzzy IPv4 address / ipv4_address, e.g. '203' or '10.130'.")
            String ipv4Address) {
        // Build a Lucene/ES query joining the provided filters with AND, e.g.
        //   (name:(*cam*) AND type_name:(*Sensor*) AND vendor_name:(*Eaton*))
        List<String> clauses = new ArrayList<>();
        addClause(clauses, "name", name);
        addClause(clauses, "type_name", type);
        addClause(clauses, "vendor_name", vendorName);
        addClause(clauses, "pline_name", plineName);
        addClause(clauses, "ipv4_address", ipv4Address);

        // No filter provided -> match all devices the user can see.
        String query = clauses.isEmpty()
                ? "name:(*)"
                : "(" + String.join(" AND ", clauses) + ")";

        // Base64-encode as required by master's search/assets API.
        String q = Base64.getEncoder().encodeToString(query.getBytes(StandardCharsets.UTF_8));
        return master.get("/search/assets", Map.of("indices", "device", "q", q));
    }

    /** Appends a {@code field:(*term*)} clause when the term is non-blank. */
    private static void addClause(List<String> clauses, String field, String value) {
        if (value == null) {
            return;
        }
        String term = value.trim();
        if (!term.isEmpty()) {
            clauses.add(field + ":(*" + term + "*)");
        }
    }
}
