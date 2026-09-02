# Master 侧接口契约 — OBO Token Exchange Gateway

> 面向 **master(bldc-blss-master-service)** 团队的前置实现契约。
> mcp-spike 在此架构中退化为**纯 MCP Gateway / 资源服务器**,不再是授权服务器。
> master 需承担三个新角色:**Core(签发 USER_TOKEN)**、**Authorization Server(token-exchange)**、**REST 后端(接受带 sub 的 Bearer JWT)**。
>
> 关联:[openspec/changes/gateway-obo-token-exchange](../openspec/changes/gateway-obo-token-exchange/)（proposal / design / specs）。

---

## 1. 全景时序

```
User(已登录 master,持 APP_SESSION)
  │  在聊天界面点"发送"
  ▼
┌──────────── Chatbot UI (浏览器) ────────────┐
│                                             │
│  ① POST <mint endpoint>  + APP_SESSION      │──▶ master
│     ◀── USER_TOKEN (15min, aud=<agent>)     │
│         每次发送前现取,前端不缓存             │
│                                             │
│  ② POST /api/chat  + Bearer USER_TOKEN      │──▶ Agent Service
└─────────────────────────────────────────────┘         │
                                                        │ (LLM + MCP client)
                    ┌── 首次:401 + 发现 ──────────────────┤
                    │                                     │ ③ 任一 MCP 请求(无 token)→ 401
                    ▼                                     │
             mcp-spike (Gateway/RS)                       │ ④ GET /.well-known/oauth-protected-resource
                    │  authorization_servers = master     │    → authorization_servers=[master]
                    └─────────────────────────────────────┘
                                                          │ ⑤ GET master 的 RFC 8414 metadata
                                                          ▼
                                           ┌────────── master (AS) ──────────┐
                                           │ ⑥ POST /oauth2/token             │
                                           │    grant=token-exchange          │
                                           │    subject_token=USER_TOKEN      │
                                           │    resource=<mcp-resource>       │
                                           │  → 验 aud/token_use/sid session  │
                                           │  → MCP_ACCESS_TOKEN  (30min)     │
                                           │  + MCP_REFRESH_TOKEN (8h)        │
                                           └────────────────┬────────────────┘
                                                            │ Bearer MCP_ACCESS_TOKEN
                                                            ▼
                                       Agent ──tools/call──▶ mcp-spike (Gateway/RS)
                                                            │ 验签(master JWKS)/iss/aud/exp
                                                            │ 同一 token 原样透传
                                                            ▼
                                           ┌────────── BLSS MCP (master 内) ──┐
                                           │ 每请求独立验签 + 读 sub           │
                                           │ 重建 UserContext + RBAC          │
                                           └─────────────────────────────────┘
```

> **拓扑要点**:浏览器**直接**调用 Agent(拓扑 A),不经由 Core 转发。因此
> `<agent-resource>` 是一个**对外可达的 URL**,Agent 与网关同处一个反向代理之后,
> 需要 CORS 与 TLS。`USER_TOKEN` 会落到前端 JS 中 —— 见 §3 的取舍说明。


---

## 2. master 需要提供的四个能力

| # | 能力 | 端点 / 位置 | 消费方 |
|---|---|---|---|
| A | 已登录浏览器 mint session-bound `USER_TOKEN` | 浏览器调用的 mint endpoint（路径待实现仓库确定） | Browser / Agent Service |
| B | RFC 8414 授权服务器元数据 + JWKS | `/.well-known/oauth-authorization-server`、`jwks_uri` | Agent、mcp-spike |
| C | RFC 8693 Token Exchange | `POST /oauth2/token` | Agent Service |
| D | REST 接受带 `sub` 的 Bearer JWT | 现有 REST API | mcp-spike Gateway |

---

## 3. 能力 A — 签发 USER_TOKEN

**触发**:已登录浏览器在**每次发送聊天消息之前**,用当前 `APP_SESSION` 调用 mint endpoint。Master 从当前认证 session 派生 `sub`,不接受调用者提供的用户身份。前端**不缓存**返回的令牌,每次现取。

**生命周期规则(15 / 5)**:

```
      ├──────────────────── 15 min ────────────────────┤
      ├────────── 稳定期:返回已记录的那个 ─────┼─ 5 min ┤
      0                                               exp
                                               ↑
                                        进入这段就重签
                                        ⇒ 发出去的令牌保证 ≥5 分钟可用
```

- 新签发的 `USER_TOKEN` 有效期 **15 分钟**,且不晚于登录 session 结束;
- master 按**登录 session**记录已签发的令牌。再次 mint 时,若剩余 **> 5 分钟**则原样返回同一个令牌;否则重签并覆盖记录;
- 因此调用方拿到的令牌**至少还有 5 分钟可用**(或到 session 结束,取较早者)。

**为什么这样设计**:

| 规则 | 解决什么 |
|---|---|
| 每次聊天前现取 | 前端无需管理过期,也不必解析 JWT |
| 过期前返回同一个 | 限制同时存活的凭据数量 —— 连发十条消息只持有一个令牌,而不是十个 |
| 剩余 ≤5 分钟即重签 | 避免把只剩几百毫秒的令牌交出去;Agent 可能在 LLM 思考若干秒之后才用它 |

> **前端的失败处理**:有了 ≥5 分钟保证,"在途过期"不再是现实场景。唯一残留的失败是
> mint 与 chat 之间登录 session 结束,它在 exchange 时表现为 `invalid_grant`,
> **归入应用现有的"会话失效 → 跳登录页"逻辑**,不需要为 MCP 定义额外的错误契约。

**必须满足的语义约束**:

1. `sub` **只能**来自当前认证 session,调用方不能指定;
2. 令牌带 `sid` claim 标识签发它的登录 session,供 exchange/refresh 回查;
3. 记录**必须以登录 session 为键**,并在 logout 时清除 —— 若以用户为键,登出后重新登录会拿回绑着已失效 `sid` 的旧令牌,导致用户刚登录成功却在最长 15 分钟内无法使用 MCP;
4. logout / session 失效后,该令牌不能再用于 token exchange;
5. `exp` = min(now + 15min, `APP_SESSION` 过期时间)。

**形态**:必须是**签名 JWT**,不能是不透明令牌。`USER_TOKEN` 确实不经过 mcp-spike 网关,但 **Agent 是它的受众** —— Agent 需要在本地完成验签、校验 `aud`、读取 `sub`/`sid` 作缓存键,不透明令牌会让这些都做不到,并迫使每次聊天都回 master 查一次。

**签名**:用 master 自己的私钥(与能力 B 的 JWKS 对应),算法 `RS256`。

**claims**:

| claim | 说明 | 示例 |
|---|---|---|
| `iss` | master 的 issuer 标识 | `https://blss.<deployment>` |
| `sub` | **真实用户标识**(贯穿全链路) | `peter` |
| `aud` | Agent Service resource | `<agent-resource>` |
| `sid` | 当前 master 登录 session 标识(exchange/refresh 时回查其有效性) | — |
| `exp` | min(签发时刻 + 15min, `APP_SESSION` 过期时间) | — |
| `iat` / `nbf` | 签发/生效时间 | — |
| `token_use` | 标记用途,便于 exchange 时区分 | `mcp_user` |

> **安全取舍(已接受)**:拓扑 A 下 `USER_TOKEN` 会落到前端 JS 中。浏览器本来就持有
> `APP_SESSION`(更强的凭据),所以增量风险不在**时长**而在**可携带性** ——
> `APP_SESSION` 是 HttpOnly、XSS 读不到且用不出浏览器;`USER_TOKEN` 可被 XSS 读取并外带使用。
> 压制手段是 15 分钟有效期 + `sid` 绑定(登出即失效)。**注意放大效应**:攻击者只要在有效期内
> 兑换一次,就能得到 30 分钟 access + 8 小时 refresh —— 这是保留 refresh token 的代价之一。


---

## 4. 能力 B — 授权服务器元数据 + JWKS

Agent 与 mcp-spike 都要能**离线验签**,因此 master 必须公开:

- **RFC 8414 元数据**:`GET /.well-known/oauth-authorization-server`
  - 至少包含:`issuer`、`token_endpoint`、`jwks_uri`、`grant_types_supported`(须含 `urn:ietf:params:oauth:grant-type:token-exchange`)。
- **JWKS**:`jwks_uri` 返回 master 的公钥集,用于验证 `USER_TOKEN` 和 `MCP_ACCESS_TOKEN` 的签名。

> mcp-spike 通过 `sidecar.issuer-uri`(发现)或直接 `sidecar.jwks-uri` 拉取此 JWKS 来验证入站的 `MCP_ACCESS_TOKEN`。

---

## 5. 能力 C — Token Exchange(RFC 8693)

**端点**:`POST /oauth2/token`

> ⚠️ **尚未实现(截至本文档修订时)**。master 的 RFC 8414 元数据已经广播
> `token_endpoint = <issuer>/oauth2/token`(该字段是必填的,广播本身没错),但**没有任何代码映射这个路径**。
>
> 现在往那里 POST 会得到 **`403 Forbidden`**,而不是 404 或 OAuth 错误 —— 因为请求落进了兜底安全链,
> `/oauth2/**` 在那里已经是 `permitAll`(`opi-security.xml:224`,排在 `/**` 之前),但 `/oauth2/token`
> **不在 CSRF 豁免清单里**,于是被 CSRF 过滤器拦下。
>
> **联调时别被这个 403 误导** —— 它看起来像权限/scope 问题,实际是端点还不存在。
> 实现时需要给它一条独立的 `create-session="stateless"` + `csrf disabled` + `permitAll` 的 `<http>` 链
> (排在兜底链之前);凭据是请求体里的 `USER_TOKEN`,不是会话,所以既不能靠 Spring Security 鉴权,也不适用 CSRF。

**请求**(`application/x-www-form-urlencoded`):

| 参数 | 值 | 说明 |
|---|---|---|
| `grant_type` | `urn:ietf:params:oauth:grant-type:token-exchange` | RFC 8693 |
| `subject_token` | `<USER_TOKEN>` | 能力 A 签发的令牌 |
| `subject_token_type` | `urn:ietf:params:oauth:token-type:jwt`(JWT 实现)或 `...:access_token`(不透明实现) | 与所选形态一致 |
| `resource` | `<mcp-resource>` | RFC 8707,目标资源标识 |

**处理**:
1. 验证 `subject_token` 有效(JWT 实现:验签 + `iss` + `exp`;不透明实现:内部查表)。
2. 验证 `aud=<agent-resource>`、`token_use=mcp_user`，并**确认其关联的 master 登录 session 仍有效**。
3. 取出 `sub`(真实用户)。
4. 签发 `MCP_ACCESS_TOKEN`,`aud=<mcp-resource>`、保留 `sub`、`exp≈30min`。
5. 同时签发 `MCP_REFRESH_TOKEN`(`exp≈8h`),供 Agent 静默续期。

Token endpoint 不要求额外 Agent client authentication；`USER_TOKEN` 是 exchange 的唯一凭据，RFC 8414 metadata 声明 `token_endpoint_auth_methods_supported=["none"]`。V1 不请求或执行 OAuth scope。

**响应**(RFC 8693 §2.2.1):

```json
{
  "access_token": "<MCP_ACCESS_TOKEN>",
  "issued_token_type": "urn:ietf:params:oauth:token-type:access_token",
  "token_type": "Bearer",
  "expires_in": 1800,
  "refresh_token": "<MCP_REFRESH_TOKEN>"
}
```

**MCP_ACCESS_TOKEN 必备 claims**:

| claim | 值 | 谁校验 |
|---|---|---|
| `iss` | master issuer | gateway + backend MCP (+ master REST if independently enabled) |
| `sub` | 真实用户(如 `peter`) | backend MCP（重建用户上下文） |
| `aud` | `<mcp-resource>` | gateway + registered backend MCP |
| `exp` | ~30min | gateway + registered backend MCP |

---

## 6. 能力 D — REST Bearer（独立能力，不是 Gateway 最终路由）

Master REST 接受 Bearer 可继续作为客户第三方软件使用的独立能力，但最终 MCP Gateway 不再通过 `MasterClient` 直调 REST；它把 token 透传给 BLSS MCP。若第三方直接调用 master REST：

```
Authorization: Bearer <MCP_ACCESS_TOKEN>
```

master REST **必须独立完成以下校验**(即使流量来自同容器 127.0.0.1):

1. **验签**:用 master 自己的签名密钥 / JWKS 验证签名。
2. **校验 `iss`** = master issuer。
3. **校验 `exp`/`nbf`**(可容许小幅时钟偏移)。
4. **接受 `aud=<mcp-resource>`**:把 `<mcp-resource>` 列入可接受受众白名单。
5. **读 `sub`** → 重建 `UserContext` → 套用现有 RBAC。

### 为什么直接 REST 调用仍必须验签

```
容器内:
  ┌─────────┐  127.0.0.1   ┌────────────┐
  │ client  │ ───────────▶ │ master REST│
  └─────────┘  Bearer JWT  └────────────┘
       ▲                         ▲
   其他本地进程 ──────────────────┘  ← 威胁:同容器任何进程都能打 127.0.0.1
```

- **没有 mTLS,localhost 不是可信边界**:同容器任何进程都能连 `127.0.0.1:<master port>`。
- 若 master 仅凭"请求来自本地"就信任 `sub`,任何本地进程都能伪造 `sub=admin`。
- **让 `sub` 可信的唯一依据是 master 自己的签名**——不是来源 IP。
- master REST 必须自行验签，不能信任来源网络或调用方传入的身份字段。

> 一句话:**localhost 省掉的是 mTLS,不是验签。**

### 附加加固建议
- master REST 端口尽量**只绑定 loopback 接口**。
- 精简同容器内运行的其他进程。

---

## 7. 已决议 / 待确认

**已决议**：
- 用户标识用标准 `sub` claim。
- master REST 接受 `aud=<mcp-resource>`,但仍完整验签(见 §6)。
- MCP Gateway 最终路由到 BLSS MCP，而不是 master REST；REST Bearer 保留为独立第三方能力。
- **拓扑 A**:浏览器**直接**调用 Agent,不经由 Core 转发。因此 `<agent-resource>` 是对外可达的 URL,Agent 与网关同处一个反向代理之后。
- **MCP `USER_TOKEN` 与现有 OTT session-handoff token 物理隔离**:不复用 OTT 的 store、provider 与 `LoginWorker`;MCP 令牌不创建登录会话,只绑定到已有会话。
- **`USER_TOKEN` 必须是签名 JWT**,不能是不透明令牌 —— Agent 是它的受众,需要本地验签并读取 `sub`/`sid`。
- **`USER_TOKEN` 生命周期 15 分钟,剩余 ≤5 分钟即重签**;前端每次聊天前现取、不缓存;master 按**登录 session**记录并在 logout 时清除(见 §3)。
- Master 单节点运行；签名密钥启动时内存生成且不落库。重启后旧 `USER_TOKEN`、MCP access/refresh token 失效是接受的当前部署行为。
- Logout 后禁止新的 token exchange；已经签发的 `MCP_ACCESS_TOKEN` 允许自然过期。
- **保留 refresh token**(绑定原始登录 session；logout/session 失效后 refresh 必须返回 `invalid_grant`)。已知它在当前设计下是冗余的 —— 每次聊天都有新鲜 `USER_TOKEN`,exchange 与 refresh 成本相同。**重新评估的触发条件**:Agent 出现超出请求生命周期的异步/后台工作。
- Token endpoint 不要求额外 Agent client authentication；V1 不使用 OAuth scope。
- **session 回查机制已确定**:master 的兜底链配了 `<concurrency-control/>`，Spring Security 因此维护一个根上下文的 `SessionRegistry`(`SessionCleaner` / `LoginWorker` / `CommonService` / `SysControl` 已在用)。回查即 `getSessionInformation(sid)`，**必须同时判 `null` 和 `isExpired()`** —— 登出会 remove，而被并发登录顶下线只会 `expireNow()` 不 remove，只判 `null` 会漏掉后者。
- **mint endpoint 挂在兜底链下的已认证路径**，不单独建 `<http>` 链 —— `/rest/**` 和 `/mcp` 都是 `create-session="stateless"`，在那种链上拿不到 session 里的 `SecurityContext`，mint 会把每个请求都当成未认证。
- BLSS MCP 后端使用 **MCP Java SDK 2.0.0 的 stateless Streamable HTTP servlet transport**，不引入 Spring AI(其 2.0 线要求 Spring Boot 4)。见 §10。

**待 master 团队确认**：
- 部署域名,以及 §10 的反向代理分流规则。
- `<agent-resource>` 的具体值,以及 browser mint endpoint 的路径与响应形状。
- 记录已签发 `USER_TOKEN` 的存储位置与清理方式(需以登录 session 为键)。

---

## 8. master 侧现状 gap 与可复用基础

> 详细提案见 master 仓库的 OpenSpec 变更 **`mcp-obo-token-issuance`**
> (`bldc-blss-master-service/openspec/changes/mcp-obo-token-issuance/`)。

### 现状缺口(三处断点)

| OBO 所需能力 | master 现状 | 结论 |
|---|---|---|
| 签发 MCP `USER_TOKEN` | ❌ 无(OTT handoff 专用,不可复用) | 新建独立链，每 session 一个、15 分钟、剩余 ≤5 分钟重签 |
| AS(RFC 8693 token-exchange) | ❌ 只有 `oauth2-client`(登录消费方) | **全新**,引入 authorization-server |
| 公开 JWKS + RFC 8414 元数据 | ❌ 无(master 是 JWKS 消费方) | **全新** |
| BLSS MCP 后端(`/mcp`) | ❌ 无 | **全新**，见 master 仓库 `mcp-blss-server` |
| REST 接受 Bearer JWT | ❌ `/rest/**` 目前是 HTTP Basic | 在 Basic 之外**叠加** bearer（**第三方能力，非网关前置依赖**） |

> **当前最直接的断点**:master 尚无 AS(签发 `MCP_ACCESS_TOKEN` + JWKS),也尚无 BLSS MCP 后端可供网关路由。
> `/rest/**` 接受 Bearer **已不再是网关的阻塞项** —— 任务 7.14 之后 `MasterClient` 已删除,网关不再直调 master REST,而是把 token 透传给 BLSS MCP。该能力保留给客户第三方软件,可从 MCP 端到端的关键路径上摘下来单独排期。

### 可复用基础(非从零)

- **OTT password-reset 场景**证明了 master 的「独立认证场景」模式:自带 store + provider + authentication-manager + 独立 `<http>` 链(物理隔离)。MCP `USER_TOKEN` 链照此搭建,但用无状态 JWT。
- **`spring-security-oauth2-jose`** 依赖已在 → JWT 编解码能力现成。
- 缺的主要是 **authorization-server**(签发/exchange)与 **resource-server**(REST 验 bearer)两块。
- **`spring-security-oauth2-authorization-server`**:正好从 mcp-spike 移除、迁到 master(AS 角色搬家)。

### 关键隔离设计(为什么不复用 OTT）

```
OTT session-handoff (现有)          MCP USER_TOKEN (新)
  /handoff/token + LoginWorker         独立链,无 LoginWorker
  一用户一个,消费后失效                每 session 一个,15 分钟内可重复取回
  桥接成完整登录会话                   不创建登录会话,仅绑定到现有会话
```

> 隔离的重点是**不复用 OTT 的 store 与 `LoginWorker`**,而不是"无状态"本身。
> MCP `USER_TOKEN` 依然需要能回查其登录 session 是否有效(见 §3 约束 2/4),
> 而且 master 需要按 session 记录已签发的令牌以实现"过期前返回同一个"(§3 约束 3),
> 因此它不是一个纯粹无状态、签发即不可撤销的令牌。

## 9. master 侧改造清单（供排期）

- [ ] 依赖:`pom.xml` 加 `spring-security-oauth2-authorization-server`(`oauth2-jose` 已在)。
- [ ] 密钥:单节点启动时内存生成 master 签名密钥(RSA/EC)，并记录重启使旧 token 失效的行为。
- [ ] Browser/USER_TOKEN:已登录浏览器在每次聊天前 mint `USER_TOKEN`（签名 JWT、`sub` 来自 session、带 `sid`、`aud=<agent-resource>`、`token_use=mcp_user`、`exp = min(now+15min, session expiry)`），不碰 OTT/`LoginWorker`。
- [ ] Browser/USER_TOKEN:按**登录 session** 记录已签发的令牌;剩余 >5 分钟原样返回,否则重签覆盖;**logout 时清除记录**(以用户为键会让重新登录后拿回绑着失效 `sid` 的旧令牌)。
- [ ] AS:公开 `/.well-known/oauth-authorization-server` + `jwks_uri`。
- [ ] AS:实现 RFC 8693 token-exchange，验证 Agent audience/token_use/**登录 session 有效性**，有效时产出 `MCP_ACCESS_TOKEN`(30min) + `MCP_REFRESH_TOKEN`(8h),`aud=<mcp-resource>`、保留 `sub`；无需额外 client authentication/scope。
- [ ] AS:支持 refresh_token 续期 `MCP_ACCESS_TOKEN`。
- [ ] AS:refresh token 绑定原始登录 session；session/logout 失效后返回 `invalid_grant`，已有 access token 自然过期。
- [ ] **BLSS MCP 后端**:见 §10 —— 用 MCP Java SDK 2.0.0 的 stateless servlet transport 暴露 `/mcp`，每请求独立验签，`tools/list` 身份无关。
- [ ] REST(**第三方能力，非网关前置依赖**):`/rest/**` 在 HTTP Basic 之外**叠加** `Authorization: Bearer <JWT>`,仅在带 bearer 时启用,独立验签(签名/iss/aud/exp)。
- [ ] REST:从 `sub` 用现有 user-loading 服务重建 `UserContext` 并套用 RBAC(与 Basic 路径一致)。
- [ ] REST:将 `<mcp-resource>` 加入可接受 `aud` 白名单;未知 `sub`/无效令牌返回 401/403。
- [ ] 兼容:确认无 bearer 时 HTTP Basic 行为完全不变。
- [ ] 部署:master REST 端口绑定 loopback。

---

## 10. BLSS MCP 后端的技术选型（已核实）

> 详细提案见 master 仓库的 OpenSpec 变更 **`mcp-blss-server`**。

**结论:不引入 Spring AI,直接用 MCP Java SDK 2.0.0。**

Spring AI 2.0 线的 `mcp-spring-webmvc:2.0.0` 硬依赖 `spring-webmvc:7.0.8`(Spring Framework 7 → Spring Boot 4)，
master 是 Boot 3.4 / Spring 6.2，除非整体升级否则不可用。而 SDK 本身没有这个约束：

| 事项 | 核实结果 |
|---|---|
| `io.modelcontextprotocol.sdk:mcp-core:2.0.0` 依赖 | slf4j、jackson-annotations 2.20、reactor-core 3.7.0、jakarta.servlet-api 6.1.0(provided) —— **零 Spring** |
| 字节码版本 | Java 17(class major 61)，master 是 Java 21 ✓ |
| Jackson | BOM 提供 `mcp-json-jackson2`，master 保持 Boot 3.4 的 Jackson 2 ✓ |
| `HttpServletStatelessServerTransport` | `extends jakarta.servlet.http.HttpServlet`,只有 `doGet`/`doPost` |
| Servlet 版本兼容 | 只用到 `getHeader/getReader/getRequestURI/getWriter/sendError/setStatus/setContentType/setCharacterEncoding(String)`,**无 Servlet 6.1 专有 API** → Tomcat 10.1(Servlet 6.0) ✓ |
| 异步要求 | stateless transport **不使用 `AsyncContext`**,无需 `async-supported`,跑在 servlet 线程上 |
| 传输形态 | 就是 **stateless Streamable HTTP**,正好满足 §1 网关契约 |
| 身份注入 | Builder 提供 `contextExtractor(McpTransportContextExtractor<HttpServletRequest>)`,tool handler 签名为 `BiFunction<McpTransportContext, CallToolRequest, CallToolResult>` |

**连带收益**:它是普通 Servlet,用 `ServletRegistrationBean` 或 `web.xml` 即可注册 ——
"Spring AI starter auto-config 在 WAR/Tomcat 下不工作"这个风险**直接消失**;
身份也可用 context 注入(机制 B)而非 `ThreadLocal`,不受线程模型影响。

**代价(需 master 认可)**:失去 Spring AI 的 `@Tool` 注解与 JSON Schema 自动推断,
工具需手写 `McpSchema.Tool`(name/description/inputSchema)与 handler,或自行接一个 schema 生成器。
