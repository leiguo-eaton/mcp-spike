## 1. Protocol version model

- [ ] 1.1 Extend `McpProtocol` with the modern revision `2026-07-28`, the legacy revisions the gateway accepts, and a predicate distinguishing modern from legacy
- [ ] 1.2 Add the `_meta` key constants (`io.modelcontextprotocol/protocolVersion`, `clientInfo`, `clientCapabilities`) and the reserved error codes (`-32020` HeaderMismatch, `-32021` MissingRequiredClientCapability, `-32022` UnsupportedProtocolVersion)
- [ ] 1.3 Confirm the gateway's own error categories stay inside the implementation-defined range `-32000`..`-32019` and do not collide with the reserved range

## 2. Gateway server side — era detection and dispatch

- [ ] 2.1 Detect the era per request: `_meta.io.modelcontextprotocol/protocolVersion` present means modern; an `initialize` request means legacy (D17)
- [ ] 2.2 Keep the existing legacy dispatch untouched, so current clients see no behavioural change
- [ ] 2.3 Add modern dispatch sharing the same tool-resolution layer as legacy, differing only in envelope, validation and status mapping
- [ ] 2.4 Answer `GET` and `DELETE` on the MCP endpoint with `405`; never mint or echo `Mcp-Session-Id`; ignore `Last-Event-ID`

## 3. Gateway server side — modern request validation

- [ ] 3.1 Require `MCP-Protocol-Version` and `Mcp-Method` on every modern request, and `Mcp-Name` on `tools/call`
- [ ] 3.2 Compare each header against its body value, decoding the `=?base64?…?=` sentinel form first
- [ ] 3.3 Reject a missing, malformed or mismatched header with HTTP `400` + `-32020`, naming the header at fault
- [ ] 3.4 Reject an unsupported declared version with HTTP `400` + `-32022`, carrying `data.requested` and `data.supported`
- [ ] 3.5 Validate the `Origin` header against a configurable allow-list, answering `403` when present and disallowed; do nothing when unconfigured or absent (D21)

## 4. Gateway server side — modern responses

- [ ] 4.1 Implement `server/discover` returning supported protocol versions, capabilities and server info, without triggering backend discovery
- [ ] 4.2 Tag every modern result with `resultType: "complete"`
- [ ] 4.3 Add `ttlMs` and `cacheScope` to the modern `tools/list` result, derived from the published snapshot's remaining lifetime and its identity-independence (D20)
- [ ] 4.4 Answer an unimplemented modern method with HTTP `404` + `-32601`, including `ping`, `subscriptions/listen`, `resources/*` and `prompts/*`
- [ ] 4.5 Keep the gateway error categories (`unknown_prefix`, `backend_unavailable`, …) working identically on both eras

## 5. Gateway client side — dual-era backend calls

- [ ] 5.1 Build modern backend requests: `_meta` protocol version and client info, plus `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` headers
- [ ] 5.2 On backend HTTP `400`, inspect the body and treat a recognized modern error (`-32020`/`-32021`/`-32022`) as "backend is modern"; anything else triggers legacy fallback
- [ ] 5.3 Fall back to the existing `initialize` + `notifications/initialized` flow for legacy backends
- [ ] 5.4 Cache the era per backend origin and re-probe when the cached assumption fails; log a fallback at WARN with the backend prefix
- [ ] 5.5 Skip the `initialize` handshake entirely for a backend determined to be modern

## 6. Configuration

- [ ] 6.1 Add the configurable `Origin` allow-list, defaulting to empty (validation disabled)
- [ ] 6.2 Confirm no production-looking defaults are introduced; deployment identifiers stay environment-supplied

## 7. Verification

- [ ] 7.1 Tests: era detection — modern `_meta` request served modern; `initialize` served legacy; both eras return the same catalog
- [ ] 7.2 Tests: header validation — each required header missing; each mismatched; base64 sentinel decoded; version header disagreeing with `_meta`
- [ ] 7.3 Tests: unsupported version returns `400` + `-32022` with `data.supported` and `data.requested`
- [ ] 7.4 Tests: `server/discover` contents, and that it triggers no backend discovery
- [ ] 7.5 Tests: `resultType` on every modern result; `ttlMs`/`cacheScope` on modern `tools/list` reflecting the snapshot
- [ ] 7.6 Tests: `404` + `-32601` for `ping`, `subscriptions/listen`, `resources/list`, `prompts/list`; `405` for `GET`/`DELETE`; no `Mcp-Session-Id` in any response
- [ ] 7.7 Tests: `Origin` allow-list — disallowed origin `403`, absent header unaffected, unconfigured allow-list unaffected
- [ ] 7.8 Tests: client-side era probe — legacy backend detected via non-modern `400` body and served via `initialize`; modern backend served without handshake; modern `400` error body does not trigger fallback; era cached per origin
- [ ] 7.9 Tests: gateway error categories behave identically under both eras
- [ ] 7.10 Regression: the existing legacy end-to-end tests continue to pass unchanged

## 8. Documentation

- [ ] 8.1 `agent-integration-guide.md`: document the modern flow (no `initialize`, per-request `_meta`, required headers, `server/discover`) alongside the legacy flow, and state which revisions the gateway accepts
- [ ] 8.2 `blss-mcp-backend-contract.md`: record that BLSS MCP remains legacy, that the gateway bridges eras, and what would change if it became modern
- [ ] 8.3 `obo-token-exchange-design.md`: note that the OAuth layer is unchanged by the revision, and that the gateway is the protocol-version boundary
- [ ] 8.4 Record the deferred `x-mcp-header` gap (D19) where an integrator will find it

## 9. Deferred — revisit when a modern backend appears

- [ ] 9.1 `x-mcp-header` parameter mirroring on the client side, and rejection of tool definitions with invalid annotations (D19)
- [ ] 9.2 Decide whether to advertise `2025-11-25` on the legacy path so an SDK-based backend negotiates the newest legacy revision
- [ ] 9.3 Re-evaluate BLSS MCP once a Java SDK implements `2026-07-28`
