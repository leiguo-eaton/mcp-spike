## Why

The Gateway must produce structured audit evidence for routed MCP operations without leaking tokens or business data. Existing diagnostic logs and token masking do not provide a stable, queryable record of who invoked which backend/tool and whether it succeeded.

## What Changes

- Emit one structured audit event for each completed `tools/call`, including correlation ID, logical caller, end-user subject, backend, tool, outcome, duration, and safe error category.
- Define caller assurance honestly: v1 records the configured `agent-service` channel as audience-bound, not as strongly authenticated client identity.
- Do not record raw USER/MCP tokens, Authorization headers, tool arguments, tool results, SQL, stack traces, or sensitive internal URLs by default.
- Define configurable audit sink and retention integration without coupling audit delivery to tool-call success.

## Capabilities

### New Capabilities
- `gateway-audit`: Structured, privacy-preserving audit events for Gateway tool calls, including field semantics, caller assurance, correlation, outcome, and failure behavior.

### Modified Capabilities
<!-- No existing baseline specs are modified. -->

## Impact

- Gateway routing/execution pipeline gains audit event creation around `tools/call` completion.
- Deployment configuration gains audit sink and delivery settings.
- Operations/security consumers receive a stable audit schema.
- No change to MCP tool results, token validation, routing, or backend authorization behavior.
