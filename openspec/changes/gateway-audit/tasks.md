## 1. Audit Model and Configuration

- [ ] 1.1 Define the structured audit event model with timestamp, trace ID, caller/assurance, subject, backend, tool, outcome, safe error category, and duration
- [ ] 1.2 Add configuration for the logical caller, data-domain mapping, audit sink, and delivery behavior
- [ ] 1.3 Implement strict field allow-listing so tokens, Authorization headers, arguments/results, SQL, stack traces, and internal URLs cannot enter audit events

## 2. Gateway Integration

- [ ] 2.1 Instrument `tools/call` completion to emit exactly one success or failure event
- [ ] 2.2 Map validation/routing/backend/authorization/execution failures to safe audit categories
- [ ] 2.3 Propagate or create a correlation/trace ID and pass it downstream where supported
- [ ] 2.4 Implement the baseline structured JSON audit sink without changing the Agent's MCP result when sink delivery fails
- [ ] 2.5 Expose audit-delivery failure metrics/logging suitable for operational alerts

## 3. Verification

- [ ] 3.1 Test complete field emission for successful and failed tool calls
- [ ] 3.2 Test `caller=agent-service` with `caller_assurance=audience-bound`
- [ ] 3.3 Test that all prohibited secret and payload values remain absent from events
- [ ] 3.4 Test that audit sink failure preserves MCP tool-call success/error semantics and raises an operational signal
- [ ] 3.5 Document event schema, field semantics, retention/sink deployment choices, and privacy restrictions
