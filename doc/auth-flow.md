# MCP Server OAuth 2.1 認證流程

這個 sidecar 同時扮演 **Authorization Server（授權伺服器）** 和 **Resource Server（資源伺服器）**，認證分成兩條「腿」（two legs）：

- **Leg A — OAuth 2.1**：VS Code（MCP 客戶端）↔ sidecar 之間的信任，用 Bearer JWT + PKCE。
- **Leg B — On-Behalf-Of（代理身分）**：sidecar → BLSS master REST，用 `blss_token` 轉成 HTTP Basic 認證，讓 master 以「真實使用者」身分套用 RBAC。

## 需要輸入的憑證

| 角色 | 需要提供什麼 | 來源 |
|------|-------------|------|
| **MCP 客戶端（VS Code）** | 無 client secret（公開客戶端），但**必須用 PKCE**（`code_challenge` / `code_verifier`）；`client_id` 由動態註冊 RFC 7591 取得，或使用預先種入的 `mcp-vscode` | `AuthorizationServerConfig.java` |
| **使用者（User）** | 在 `/login` 頁面輸入**帳號 / 密碼**（POC 預設 `mcp` / `mcp-pass`）作為 resource owner 登入 | `SecurityConfig.java`, `application.yml` |
| **BLSS token** | 使用者**不需輸入**；由設定檔提供固定值，見下方 | `application.yml` |

## blss-token 是如何設置的

1. **設定來源**：`sidecar.blss-user-token`（`application.yml`），是 `user:password` 的 Base64（目前預設值解碼後為 `gl:...`）。
2. **寫入 JWT**：發 token 時，`jwtTokenCustomizer` 把這個值當作 `blss_token` claim 塞進存取權杖（access token）。同時把 `aud` 綁定到 RFC 8707 的 `resource`（`<issuer>/mcp`）。
3. **取回**：資源伺服器驗證 JWT 後，`UserTokenCaptureFilter` 從 `blss_token` claim 讀出（找不到時退回讀 `X-BLSS-User-Token` 標頭），存進 `ThreadLocal`（`UserTokenContext`）。
4. **轉發**：工具呼叫時，`MasterClient` 讀出該值，以 `Authorization: Basic <blss_token>` 呼叫 master REST。

> 補充：access token 存活 12 小時（`ACCESS_TOKEN_TTL`），且互動式同意（consent）被關閉，因此 `/oauth2/authorize` 授權後直接跳回 loopback，避免 VS Code 反覆彈出 "Allow"。

---

## Mermaid 序列圖

```mermaid
sequenceDiagram
    autonumber
    actor User as 使用者 (瀏覽器)
    participant VSCode as MCP 客戶端 (VS Code)
    participant RS as Sidecar 資源伺服器 (/mcp)
    participant AS as Sidecar 授權伺服器 (OAuth 2.1)
    participant Master as BLSS Master REST

    Note over VSCode,RS: Leg A — 探索與挑戰 (RFC 9728)
    VSCode->>RS: POST /mcp (無 Bearer token)
    RS-->>VSCode: 401 WWW-Authenticate: Bearer resource_metadata="..."
    VSCode->>RS: GET /.well-known/oauth-protected-resource
    RS-->>VSCode: { resource, authorization_servers:[issuer], scopes:[mcp.read, mcp.invoke] }
    VSCode->>AS: GET /.well-known/oauth-authorization-server (RFC 8414)
    AS-->>VSCode: 端點清單 (authorize / token / jwks / register)

    Note over VSCode,AS: 動態客戶端註冊 (RFC 7591, 匿名)
    VSCode->>AS: POST /connect/register (public client, scopes)
    AS-->>VSCode: client_id (公開客戶端, 需 PKCE)

    Note over User,AS: Leg A — 授權碼 + PKCE
    VSCode->>AS: GET /oauth2/authorize (code_challenge, resource=<issuer>/mcp, scope)
    AS-->>User: 302 轉址到 /login
    User->>AS: 輸入帳號/密碼 (mcp / mcp-pass)
    AS-->>VSCode: 302 回 loopback 127.0.0.1:33418 + authorization code
    VSCode->>AS: POST /oauth2/token (code + code_verifier)
    Note right of AS: jwtTokenCustomizer:<br/>aud = <issuer>/mcp<br/>claim blss_token = 設定值
    AS-->>VSCode: access_token (JWT, 12h, 含 blss_token claim)

    Note over VSCode,Master: 已認證的工具呼叫 (Leg A + Leg B)
    VSCode->>RS: POST /mcp (Authorization: Bearer <JWT>)
    RS->>RS: 驗證 JWT (jwtDecoder)
    RS->>RS: UserTokenCaptureFilter 讀 blss_token → UserTokenContext
    RS->>Master: GET /rest/... (Authorization: Basic <blss_token>)
    Master-->>RS: JSON 結果 (套用該使用者的 RBAC)
    RS-->>VSCode: MCP 工具結果
```

---

## PlantUML 序列圖

```plantuml
@startuml
autonumber
actor "使用者 (瀏覽器)" as User
participant "MCP 客戶端\n(VS Code)" as VSCode
participant "Sidecar 資源伺服器\n(/mcp)" as RS
participant "Sidecar 授權伺服器\n(OAuth 2.1)" as AS
participant "BLSS Master REST" as Master

== Leg A — 探索與挑戰 (RFC 9728) ==
VSCode -> RS : POST /mcp (無 Bearer token)
RS --> VSCode : 401 WWW-Authenticate:\nBearer resource_metadata="..."
VSCode -> RS : GET /.well-known/oauth-protected-resource
RS --> VSCode : { resource, authorization_servers:[issuer],\n  scopes:[mcp.read, mcp.invoke] }
VSCode -> AS : GET /.well-known/oauth-authorization-server\n(RFC 8414)
AS --> VSCode : 端點清單 (authorize / token / jwks / register)

== 動態客戶端註冊 (RFC 7591, 匿名) ==
VSCode -> AS : POST /connect/register (public client, scopes)
AS --> VSCode : client_id (公開客戶端, 需 PKCE)

== Leg A — 授權碼 + PKCE ==
VSCode -> AS : GET /oauth2/authorize\n(code_challenge, resource=<issuer>/mcp, scope)
AS --> User : 302 轉址到 /login
User -> AS : 輸入帳號/密碼 (mcp / mcp-pass)
AS --> VSCode : 302 回 loopback 127.0.0.1:33418\n+ authorization code
VSCode -> AS : POST /oauth2/token (code + code_verifier)
note right of AS
  jwtTokenCustomizer:
  aud = <issuer>/mcp
  claim blss_token = 設定值
end note
AS --> VSCode : access_token (JWT, 12h,\n含 blss_token claim)

== 已認證的工具呼叫 (Leg A + Leg B) ==
VSCode -> RS : POST /mcp (Authorization: Bearer <JWT>)
RS -> RS : 驗證 JWT (jwtDecoder)
RS -> RS : UserTokenCaptureFilter 讀 blss_token\n→ UserTokenContext
RS -> Master : GET /rest/...\n(Authorization: Basic <blss_token>)
Master --> RS : JSON 結果 (套用該使用者的 RBAC)
RS --> VSCode : MCP 工具結果
@enduml
```
