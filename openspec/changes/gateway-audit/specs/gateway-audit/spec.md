## ADDED Requirements

### Requirement: Emit a structured audit event for every tool call
The Gateway SHALL emit one structured audit event after every `tools/call` attempt completes, whether routing/execution succeeds or fails. The event SHALL contain timestamp, correlation or trace ID, logical caller, caller assurance, end-user `sub`, backend prefix when resolved, backend-native tool name when resolved, outcome, safe error category when applicable, and duration.

#### Scenario: Successful tool call is audited
- **WHEN** a routed backend tool call succeeds
- **THEN** the Gateway emits one event identifying the subject, backend, tool, success outcome, correlation ID, and duration

#### Scenario: Failed tool call is audited
- **WHEN** a tool call fails validation, routing, backend availability, authorization, or execution
- **THEN** the Gateway emits one event with a failure outcome and safe error category

### Requirement: Represent caller assurance accurately
In v1 the Gateway SHALL record the configured logical caller as `agent-service` with `caller_assurance=audience-bound`. It SHALL NOT represent that value as strongly authenticated OAuth client identity unless a future verified client credential or signed client claim is introduced.

#### Scenario: V1 caller is recorded without overclaiming assurance
- **WHEN** an Agent-originated tool call is audited under the USER_TOKEN-only exchange model
- **THEN** caller is `agent-service` and caller assurance is `audience-bound`

### Requirement: Exclude secrets and sensitive payloads
Audit events SHALL NOT contain raw USER, access, or refresh tokens; Authorization headers; raw tool arguments or results; SQL; stack traces; or internal backend URLs. Data domain, when required, SHALL be derived from allow-listed backend/tool configuration rather than payload inspection.

#### Scenario: Sensitive call remains redacted
- **WHEN** a tool call contains a token, SQL, sensitive arguments, result data, or an internal exception
- **THEN** none of those raw values appear in the audit event

### Requirement: Audit delivery does not alter tool-call semantics
An audit sink failure SHALL NOT replace or modify the MCP success/error returned for the tool call. The Gateway SHALL expose audit delivery failure through operational metrics or alerts.

#### Scenario: Audit sink fails after successful execution
- **WHEN** a backend tool succeeds but the audit sink write fails
- **THEN** the Agent still receives the successful tool result
- **AND** the Gateway records an operational audit-delivery failure signal
