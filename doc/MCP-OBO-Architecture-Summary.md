# BLSS MCP OBO Architecture Summary

> Current BLSS architecture summary. Detailed behavior is defined by the OpenSpec changes and
> `obo-token-exchange-design.md`.

## Architecture

```text
User
  -> Browser mints USER_TOKEN from master session
       -> Agent Service directly (session-bound USER_TOKEN)
            -> master Authorization Server (RFC 8693 exchange / refresh)
            -> MCP Gateway (MCP_ACCESS_TOKEN)
                 -> BLSS MCP (same MCP_ACCESS_TOKEN)
                 -> Superset MCP (same MCP_ACCESS_TOKEN)
```

| Component | Primary responsibility |
|-----------|------------------------|
| Core / master | Let the logged-in browser mint one `USER_TOKEN` per session and validate `sid` during exchange |
| Agent Service | LLM execution, MCP client, token exchange/refresh, `sub + sid + resource` token cache |
| Master AS | Token endpoint, JWKS, authorization-server metadata |
| MCP Gateway | JWT validation, lazy tool aggregation, namespace routing, token pass-through |
| Backend MCP | Independent JWT validation, business authorization, data access, audit |

## Token Flow

| Token | Typical TTL | Purpose |
|-------|-------------|---------|
| `USER_TOKEN` | Login session lifetime | Browser-to-Agent credential and RFC 8693 subject token; stable per session |
| `MCP_ACCESS_TOKEN` | 30 minutes | Calls the Gateway and is passed unchanged to backend MCP servers |
| `MCP_REFRESH_TOKEN` | 8 hours | Lets the Agent renew the access token |

All backends share the Gateway MCP resource as the access-token audience. The Gateway does not mint
tokens and does not perform a second downstream exchange.

## Agent Authentication Sequence

```text
1. POST /mcp without token -> 401 + resource_metadata
2. GET Gateway protected-resource metadata -> resource + master AS
3. GET master authorization-server metadata -> token endpoint + JWKS
4. Exchange USER_TOKEN -> MCP_ACCESS_TOKEN + MCP_REFRESH_TOKEN
5. Call /mcp with Authorization: Bearer MCP_ACCESS_TOKEN
```

The browser sends its current session token directly to Agent. The Agent validates master signature,
issuer, Agent audience, time bounds, `token_use`, `sub`, and `sid`. When refresh fails, it reuses that
token for exchange; master verifies the referenced session is still active. Logout prevents future
exchange, while an issued MCP access token expires naturally.

## Tool Aggregation

The Agent sees one MCP endpoint and a runtime-generated namespaced catalog:

```text
blss__query_asset
blss__get_device_health
superset__run_sql
superset__list_dashboards
```

These names are examples. The actual inventory comes from backend `tools/list` responses.

The Gateway does not discover tools at startup. The first authenticated `tools/list`, or a direct
`tools/call` before a catalog exists, lazily discovers all configured backends using that request's
validated access token. The global catalog is identity-independent; backend RBAC is applied during
`tools/call`.

The refresh interval is a freshness TTL. Once stale, the next authenticated catalog-dependent
request performs refresh. There is no token-dependent background refresh, and the Gateway does not
retain an end-user token. Concurrent loads share one in-flight operation and publish catalog/routes
atomically.

## Availability

- Partial initial success publishes a partial catalog.
- Complete initial failure returns an MCP error and remains retryable.
- Later refresh failure retains per-backend last-known-good definitions.
- A call to an unavailable backend fails only that call.

## Security Boundaries

- Gateway validates signature, issuer, audience, and token time bounds.
- Gateway forwards the same token unchanged and uses no shared credential.
- Every backend independently validates the token before trusting `sub`.
- Identity never comes from LLM-controlled tool arguments.
- Tokens are masked in logs.
- Gateway v1 advertises and aggregates only `tools`, not resources/prompts/logging/completions.

## Current Delivery Boundary

- Gateway Resource Server validation is mostly implemented in `mcp-spike`; the local REST demo path will be removed.
- BLSS-first lazy aggregation and routing remain implementation work in tasks section 7.
- Master token issuance and backend JWT acceptance are external prerequisites.
- Current local `get_topology` and `search_devices` tools are POC/demo tools, not the final catalog.
- Superset MCP integration is deferred until its master-dependent authentication is known.
- Gateway audit is current delivery scope but will be specified in a separate `gateway-audit` change.
