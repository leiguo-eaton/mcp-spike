## ADDED Requirements

### Requirement: Serve both protocol eras from one endpoint
The gateway SHALL serve MCP revision `2026-07-28` (the *modern* era) and the `initialize`-handshake
revisions it already supports (the *legacy* era) on the same `/mcp` endpoint. A request carrying
`_meta.io.modelcontextprotocol/protocolVersion` SHALL be served under the modern era; an
`initialize` request SHALL select the legacy era. Era-specific rules — header validation, result
envelope, cache hints and HTTP status mapping — SHALL apply only to the era of that request.

#### Scenario: A modern request is served under the new revision
- **WHEN** a request carries `_meta.io.modelcontextprotocol/protocolVersion` of a supported modern version
- **THEN** the gateway serves it without requiring any prior handshake

#### Scenario: A legacy client keeps working unchanged
- **WHEN** a client performs `initialize` and then calls `tools/list` without `_meta`
- **THEN** the gateway answers both as it did before this change
- **AND** it does not require `Mcp-Method` or `Mcp-Name` headers on those requests

#### Scenario: Both eras see the same catalog
- **WHEN** a modern client and a legacy client each call `tools/list`
- **THEN** both receive the same aggregated, prefixed tool definitions

### Requirement: Validate the modern request metadata headers
On a modern request the gateway SHALL require the `MCP-Protocol-Version` and `Mcp-Method` headers,
and additionally `Mcp-Name` on `tools/call`. Each SHALL match the corresponding value in the request
body, decoding the `=?base64?…?=` sentinel form before comparison. A missing, malformed or mismatched
header SHALL be rejected with HTTP `400` and JSON-RPC error `-32020`.

#### Scenario: A missing required header is rejected
- **WHEN** a modern request omits `Mcp-Method`
- **THEN** the gateway responds `400` with JSON-RPC error code `-32020`
- **AND** the message names the header at fault

#### Scenario: A header that disagrees with the body is rejected
- **WHEN** `Mcp-Name` names a different tool than `params.name`
- **THEN** the gateway responds `400` with JSON-RPC error code `-32020`
- **AND** it does not route the call

#### Scenario: A base64-encoded header value is decoded before comparison
- **WHEN** `Mcp-Name` carries the `=?base64?…?=` sentinel form of the body's tool name
- **THEN** the gateway accepts the request

#### Scenario: The protocol version header must agree with the body
- **WHEN** `MCP-Protocol-Version` and `_meta.io.modelcontextprotocol/protocolVersion` differ
- **THEN** the gateway responds `400` with JSON-RPC error code `-32020`

### Requirement: Reject unsupported protocol versions with the supported list
When a modern request declares a protocol version the gateway does not implement, the gateway SHALL
respond with HTTP `400` and JSON-RPC error `-32022`, whose `data` carries the `requested` version and
the `supported` versions.

#### Scenario: An unknown version is rejected informatively
- **WHEN** a request declares protocol version `1900-01-01`
- **THEN** the gateway responds `400` with error code `-32022`
- **AND** `error.data.supported` lists the versions the gateway implements
- **AND** `error.data.requested` echoes the rejected version

### Requirement: Implement server/discover
The gateway SHALL implement the `server/discover` RPC, returning its supported protocol versions, its
advertised capabilities, and its identity, without requiring any prior request.

#### Scenario: A client discovers the gateway up front
- **WHEN** a client calls `server/discover` as its first request
- **THEN** the gateway returns its supported protocol versions, capabilities and server info
- **AND** it performs no backend tool discovery

#### Scenario: Advertised capabilities remain tools-only
- **WHEN** `server/discover` returns capabilities
- **THEN** they include `tools`
- **AND** they do not include `resources`, `prompts`, `logging`, or `completions`

### Requirement: Use the revision's result envelope and status semantics
On the modern path every result SHALL carry `resultType: "complete"`, and `tools/list` SHALL
additionally carry the `ttlMs` and `cacheScope` cache hints. An unimplemented method SHALL be
answered with HTTP `404` and JSON-RPC error `-32601`. `ping` SHALL NOT be served on the modern path.

#### Scenario: Results are tagged complete
- **WHEN** a modern `tools/list` or `tools/call` succeeds
- **THEN** the result carries `resultType` of `"complete"`

#### Scenario: tools/list advertises its freshness
- **WHEN** a modern client calls `tools/list`
- **THEN** the result carries `ttlMs` derived from the remaining lifetime of the published catalog snapshot
- **AND** `cacheScope` is `"public"`, because the catalog is identity-independent

#### Scenario: An unimplemented method returns 404
- **WHEN** a modern client calls a method the gateway does not implement, such as `subscriptions/listen`
- **THEN** the gateway responds HTTP `404` with JSON-RPC error code `-32601`

#### Scenario: ping is not part of the modern surface
- **WHEN** a modern client calls `ping`
- **THEN** the gateway responds HTTP `404` with JSON-RPC error code `-32601`

### Requirement: Retire session and stream endpoints on the modern surface
The gateway SHALL NOT mint or echo `Mcp-Session-Id`, SHALL ignore `Last-Event-ID`, and SHALL answer
HTTP `GET` and `DELETE` on the MCP endpoint with `405 Method Not Allowed`.

#### Scenario: Session and stream verbs are refused
- **WHEN** a client sends `GET` or `DELETE` to the MCP endpoint
- **THEN** the gateway responds `405`

#### Scenario: A stale session header is ignored, not honoured
- **WHEN** a request carries an `Mcp-Session-Id` header
- **THEN** the gateway processes the request normally
- **AND** the response carries no `Mcp-Session-Id`

### Requirement: Validate the Origin header when an allow-list is configured
The gateway SHALL respond `403 Forbidden` when an allow-list of origins is configured and a request
carries an `Origin` header outside it. When no allow-list is configured, or the header is absent, the
gateway SHALL NOT reject on this basis.

#### Scenario: A disallowed browser origin is refused
- **WHEN** an allow-list is configured and a request carries an `Origin` outside it
- **THEN** the gateway responds `403`

#### Scenario: A server-side client without an Origin header is unaffected
- **WHEN** a request carries no `Origin` header
- **THEN** the gateway processes it normally, whether or not an allow-list is configured

### Requirement: Consume both eras from backend MCP servers
The gateway SHALL attempt a modern request to a backend first. On HTTP `400` it SHALL inspect the
body: a recognized modern JSON-RPC error identifies a modern backend, and anything else SHALL cause
fallback to the `initialize` handshake. The determined era SHALL be cached per backend origin and
re-probed if the cached assumption later fails.

#### Scenario: A legacy backend is detected and used
- **WHEN** the gateway sends a modern request to a backend that answers `400` without a modern error body
- **THEN** the gateway falls back to `initialize` and completes the operation
- **AND** it records that origin as legacy for subsequent requests

#### Scenario: A modern backend is used without a handshake
- **WHEN** a backend answers the modern request successfully
- **THEN** the gateway records that origin as modern
- **AND** it sends no `initialize` to that backend

#### Scenario: A modern error is not mistaken for a legacy backend
- **WHEN** a backend answers `400` with a recognized modern error such as `-32022`
- **THEN** the gateway treats the backend as modern
- **AND** it does not fall back to `initialize`

#### Scenario: The era determination is not repeated per request
- **WHEN** several operations run against the same backend origin
- **THEN** the era is probed once and reused
