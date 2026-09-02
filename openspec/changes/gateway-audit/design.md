## Context

The Gateway validates user tokens and routes namespaced `tools/call` operations to BLSS MCP. Jira delivery requires audit evidence, while the current change only masks tokens in diagnostic logs. V1 has no independent Agent client authentication: caller identity is inferred from the Agent-bound token/channel and cannot be represented as cryptographically strong client identity.

## Goals / Non-Goals

**Goals:**
- Emit a stable structured event for every completed Gateway `tools/call` attempt.
- Distinguish logical caller, end-user subject, backend, and tool.
- Correlate audit across Gateway/backend while minimizing sensitive data.
- Keep audit failures from changing tool-call semantics.

**Non-Goals:**
- Recording prompts, raw arguments, SQL, complete results, or tokens.
- Providing strong Agent instance authentication in v1.
- Auditing OAuth token issuance or browser login; those belong to master/Agent.
- Selecting an enterprise SIEM product in this change.

## Decisions

### D1 - One completion event per tools/call
Emit after routing/execution completes or fails. The event contains timestamp, trace/correlation ID, configured logical caller, caller assurance, `sub`, backend prefix, backend-native tool name, outcome, safe error category, and duration.

### D2 - Caller is a logical audience-bound channel
V1 records `caller=agent-service` and `caller_assurance=audience-bound`. It does not claim a specific process was authenticated because token exchange uses USER_TOKEN without Agent client authentication. If strong client authentication is added later, audit can use a verified `client_id`/`azp`.

### D3 - Data minimization by default
Never record USER_TOKEN, MCP_ACCESS_TOKEN, refresh token, Authorization headers, raw arguments/results, SQL, stack traces, or internal backend URLs. Data domain is derived from configured backend/tool metadata rather than payload inspection.

### D4 - Audit delivery is non-blocking to business outcome
Failure to write an audit event is observable through metrics/alerts but does not replace a successful or failed MCP tool result. The sink adapter and buffering policy are configurable; structured JSON application audit logs are the baseline sink.

## Risks / Trade-offs

- **Caller is not strongly authenticated** -> record explicit assurance and do not overstate identity.
- **Audit sink outage loses evidence** -> expose delivery failures and alert; durable buffering can be a later deployment enhancement.
- **Sensitive values enter errors** -> use allow-listed fields and safe Gateway error categories only.
- **High request volume** -> one compact event per call; no payload capture.
