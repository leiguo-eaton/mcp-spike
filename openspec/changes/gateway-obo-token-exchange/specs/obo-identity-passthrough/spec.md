## ADDED Requirements

### Requirement: Forward the delegated user identity to the target backend MCP server
When invoking a backend MCP server for discovery or tool execution, the gateway SHALL send the current request's validated `MCP_ACCESS_TOKEN` unchanged as `Authorization: Bearer <token>` (On-Behalf-Of). The backend SHALL independently validate that token and use its `sub` for user-specific authorization during `tools/call`. The gateway SHALL NOT use a shared or hard-coded downstream credential.

#### Scenario: Delegated identity is forwarded on a tool call
- **WHEN** an authenticated MCP tool call is routed to a backend MCP server
- **THEN** the outbound request carries `Authorization: Bearer <the validated access token>`
- **AND** no static `blss_token` / `Basic` credential is sent

#### Scenario: Current identity is forwarded during discovery
- **WHEN** an authenticated request triggers backend `initialize` or `tools/list`
- **THEN** each outbound backend request carries that triggering request's validated access token
- **AND** the token is discarded after the discovery operation rather than retained in gateway state

#### Scenario: Missing identity does not fall back to a shared credential
- **WHEN** no delegated user identity is available on the request
- **THEN** the gateway does not substitute a shared or configured credential for a backend MCP call

### Requirement: Do not leak the delegated token in logs
The gateway SHALL NOT write the raw delegated token to logs. Any diagnostic logging of the token SHALL mask it (e.g., a short prefix plus length).

#### Scenario: Token is masked in diagnostics
- **WHEN** the gateway logs an outbound backend MCP call that includes a delegated token
- **THEN** the log shows only a masked form of the token, never the full value
