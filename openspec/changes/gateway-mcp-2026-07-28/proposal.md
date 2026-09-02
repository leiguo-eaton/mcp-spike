## Why

The MCP specification's **current** revision is `2026-07-28`. Our gateway implements `2025-06-18`.

`2026-07-28` is not an incremental change — it removes the `initialize` handshake, protocol-level
sessions, the GET stream endpoint and `ping`, and moves protocol version, client identity and
capabilities into per-request `_meta` plus mandatory HTTP headers. A client written against it cannot
talk to our gateway at all: the spec's own compatibility matrix rates "Modern client → Legacy server"
as **Fails**.

At the same time, **no Java MCP SDK implements `2026-07-28` yet**. The latest release
(`io.modelcontextprotocol.sdk:mcp-core:2.0.1`, published three weeks *after* the revision) still tops
out at `2025-11-25`. BLSS MCP is built on that SDK, so it cannot become modern by upgrading a
dependency.

The specification anticipates exactly this situation and defines a **dual-era** implementation that
serves both. Our gateway is the natural place to put that boundary: it already speaks raw JSON-RPC on
both sides rather than delegating to an SDK, so it is free to implement either era.

## What Changes

- The gateway's **server side** becomes dual-era. A request carrying
  `_meta.io.modelcontextprotocol/protocolVersion` is served under `2026-07-28`; an `initialize`
  request selects the legacy revisions we support today. Both are served on the same endpoint.
- Modern requests get the full `2026-07-28` contract: mandatory `MCP-Protocol-Version`, `Mcp-Method`
  and `Mcp-Name` headers validated against the body, `server/discover`, `resultType` on every result,
  `ttlMs`/`cacheScope` on `tools/list`, and the revision's HTTP status semantics (`404` for an
  unknown method, `400` for header and version failures).
- The gateway's **client side** becomes dual-era. It attempts a modern request first and falls back
  to the `initialize` handshake when a `400` comes back without a recognized modern error body,
  caching that determination per backend origin.
- **BLSS MCP is not changed.** It stays legacy until a Java SDK supports the revision. The gateway
  bridges: a modern Agent talks `2026-07-28` to the gateway, which talks `2025-06-18` to BLSS MCP.
- `ping` and the GET/DELETE session endpoints are removed from the modern surface; `DELETE` now
  answers `405` instead of `204`.

## Capabilities

### New Capabilities
- `mcp-protocol-compatibility`: The gateway serves and consumes both the modern (`2026-07-28`,
  per-request metadata) and legacy (`initialize` handshake) protocol eras, negotiating per request on
  the server side and per backend origin on the client side.

### Modified Capabilities
- `mcp-tool-aggregation`: `tools/list` gains the revision's cache hints, and the catalog is served
  under both eras from one snapshot.

## Impact

- **mcp-spike code**: `McpGatewayController` splits into an era-detecting front with modern and
  legacy dispatch paths; new header validation, `server/discover`, and error mapping.
  `StreamableHttpBackendMcpClient` gains modern-first request construction with legacy fallback and
  per-origin era caching.
- **Agent Service (external)**: may now be written against `2026-07-28`. Clients on `2025-06-18`
  keep working unchanged.
- **BLSS MCP (external, master)**: no change required. Revisit when the Java SDK ships
  `2026-07-28` support.
- **Docs**: `agent-integration-guide.md` describes the legacy `initialize` flow and must document the
  modern one; `blss-mcp-backend-contract.md` must state that BLSS MCP is expected to remain legacy
  for now and that the gateway bridges.
- **Not in scope**: `subscriptions/listen`, MRTR (`InputRequiredResult`), the Tasks extension, and
  `x-mcp-header` parameter mirroring. The gateway advertises only `tools`, no backend emits
  `x-mcp-header`, and no backend pushes notifications.
