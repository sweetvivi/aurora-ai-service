# 登录鉴权接口（`auth`）

> 状态：已实现。用户名 + 密码的注册、登录、令牌刷新、登出与当前账号。
> 涉及代码：`modules/auth`（Controller / AuthService / AuthSessionService / TokenService / CurrentUserResolver / 实体仓储）、`common/config/PasswordEncoderConfig`。
> 对应 `docs/ai1foo-rebuild-plan.md` §8.1「登录/会话」与 §10 契约表的「登录」行。

---

## 1. 接口总览

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| `GET` | `/api/v1/auth/registration-options` | 否 | 注册表单要渲染邮箱/验证码吗 |
| `GET` | `/api/v1/auth/captcha` | 否 | 取注册图形验证码 |
| `POST` | `/api/v1/auth/email-code` | 否 | 让 New API 给注册邮箱发验证码 |
| `POST` | `/api/v1/auth/register` | 否 | 注册并直接签发令牌 |
| `POST` | `/api/v1/auth/login` | 否 | 登录 |
| `POST` | `/api/v1/auth/refresh` | 否（凭 refresh token） | 轮换令牌对 |
| `POST` | `/api/v1/auth/logout` | 是 | 撤销当前会话 |
| `GET` | `/api/v1/auth/me` | 是 | 当前账号 |

**账号始终归属于请求解析出的站点**（`app-name` 头 → Host → 默认站点，见 `docs/app-config-api.md` §2），不接受客户端传入的站点或用户 ID —— 落实规划里「不能信任任意客户端 Uid」这一条。

## 2. 令牌模型

两种令牌刻意做成不同形态：

| | access token | refresh token |
|---|---|---|
| 形态 | HS256 签名的 JWT | 32 字节随机串（base64url） |
| 有效期 | 30 分钟（`app.auth.access-token-ttl`） | 30 天（`app.auth.refresh-token-ttl`） |
| 服务端存储 | 不存 | 只存 SHA-256（`auth_sessions.token_hash`，`CHAR(64)`） |
| 校验方式 | 验签 + `exp`，再校验会话是否仍然有效 | 按哈希查会话行 |

access token 用 JWT 是为了避免每次请求都查库解析身份；但它**不能被单独吊销**，所以每次请求仍会校验 `auth_sessions` 中对应会话的状态 —— 这样「登出立即生效」而不是等到令牌自然过期。

refresh token 是不透明随机串，只存哈希，因此**数据库泄漏也无法直接重放**。

## 3. 注册

`POST /api/v1/auth/register`

```json
{ "username": "alice", "password": "correct-horse-battery", "nickname": "Alice",
  "email": "alice@example.com", "emailCode": "483920",
  "captchaId": "9f2c4d1e8b7a6c5d4e3f2a1b0c9d8e7f", "captchaCode": "K7XQ", "clientType": "web" }
```

| 字段 | 规则 |
|---|---|
| `username` | **3–20 位**，仅字母数字与 `_ . -`；同一站点内唯一，且**同时是 New API 的用户名**（上限 20 由 New API 决定，超过会在远端建号时被拒） |
| `password` | 8–72 个字符，且 UTF-8 字节数 ≤ 72；用 BCrypt 存储 |
| `nickname` | 可选，缺省取登录名 |
| `email` | New API 开启邮箱验证时**必填**；其它情况下可选，落库但**不算已验证** |
| `emailCode` | New API 开启邮箱验证时**必填**，是邮件里的 6 位数字，由 `POST /auth/email-code` 触发发送 |
| `captchaId` / `captchaCode` | 取自 `GET /auth/captcha`，见下方「图形验证码」；`app.captcha.enabled=false` 时可不传 |
| `clientType` | 可选，记录到会话上便于设备管理 |

成功返回令牌对（见 §7）。响应中**不含任何密码相关字段**。

登录名冲突返回 `409`，共有两个来源，前端按同一文案处理即可（都表示"换一个用户名"）：

- 本站已有同名账号；
- New API 已有同名用户且不属于本账号 —— 此时会**拒绝注册**，而不是建出一个后续无法调用模型的账号。

用户名的比较是**大小写敏感**的，后端不做折叠，前端也不应擅自 `toLowerCase()`；否则用户以为自己注册的是
`Alice`，实际拿到的是 `alice`。首尾空白会被后端 `strip()` 掉。

### 注册表单能力（决定渲染哪些字段）

`GET /api/v1/auth/registration-options`

```json
{ "emailVerificationRequired": true, "emailCodeAvailable": true, "captchaRequired": true }
```

注册页在渲染表单前调用一次。**是否要求邮箱由上游决定，浏览器无法自行判断**：没有这个接口，前端要么
永远显示一个该部署根本用不上的邮箱框，要么等用户点「获取验证码」拿到一次 `404` 才知道。

| 字段 | 含义 |
|---|---|
| `emailVerificationRequired` | 注册必须带 `email` + `emailCode`，判定与 `POST /auth/register` 完全一致（含 `register-mode` 的 `ON` 强制与 `OFF` 关闭） |
| `emailCodeAvailable` | `POST /auth/email-code` 真能发信（不返回 `404`）：要求本站已接入 New API 注册**且**上游开启了邮箱验证 |
| `captchaRequired` | 注册需要图形验证码，等价于 `GET /auth/captcha` 不返回 `404` |

**两个邮箱字段不能合并**，它们回答的是不同问题：`emailVerificationRequired` 是「表单要不要收邮箱」，
`emailCodeAvailable` 是「这个部署能不能把码寄出去」。`register-mode=ON` 而上游没开邮箱验证时，
结果是 `required=true` + `available=false` —— 此时发码必然失败，属部署配置错误。前端应**照常渲染**
邮箱与验证码字段（否则用户没有任何可填写的路径），但把「获取验证码」置灰并提示服务端未启用邮箱验证，
不要把它当成「不需要邮箱」。

渲染规则：

- `emailVerificationRequired=false` → 不渲染邮箱与验证码字段（两者都可选）；
- `emailVerificationRequired=true` → 渲染邮箱与验证码；`emailCodeAvailable=false` 时禁用发码按钮并提示；
- `captchaRequired=false` → 不渲染图形验证码；
- 兜底：判断有偏差时注册仍返回 `400 请填写邮箱地址` / `请填写邮箱验证码` / 验证码错误。

`AUTH_NEW_API_REGISTRATION_ENABLED=false`（纯本地注册）时本接口不会访问上游，直接答 `false`
—— 此时 `POST /auth/email-code` 也返回 `404`，两者保持一致。
上游状态有 1 分钟缓存，上游不可达时沿用上一次结论。

### 邮箱验证（由 New API 承担）

本站**没有邮件发送能力，也不保存验证码**。邮箱验证只有一份实现，在上游：

```text
GET  /api/v1/auth/email-code  ──转发──▶  GET  {NEW_API}/api/verification?email=…
                                            │  New API 生成 6 位码，存进程内存，用它的 SMTP 发信
                                            ▼
POST /api/v1/auth/register    ──转发──▶  POST {NEW_API}/api/user/register
                                            { username, password, email, verification_code }
                                            码对 → 建号并写入 users.email；码错 → 拒绝
```

因此「邮箱已验证」的唯一含义是：**New API 接受了那一次验证码**。本站不生成码、不比对码、不落任何码表；
`user_accounts.email_verified` 只是那次结果的记录。

**`POST /api/v1/auth/email-code`**

```json
{ "email": "alice@example.com" }
```

| 状态 | 含义 | 前端动作 |
|---|---|---|
| `204` | 已请求上游发送 | 开始 60 秒倒计时 |
| `400` | 邮箱格式不正确 | 提示改邮箱 |
| `404` | 上游未开启邮箱验证 | 隐藏邮箱与验证码输入框 |
| `409` | 该邮箱已在本站或 New API 被占用 | 提示换邮箱 |
| `429` | 请求过于频繁，带 `Retry-After` | 禁用按钮直到倒计时结束 |
| `503` | 上游不可用 / SMTP 未配置 | 提示稍后重试 |

**什么时候必须带邮箱**由上游决定，不由前端决定：

| `app.auth.email-verification.register-mode` | 行为 |
|---|---|
| `AUTO`（默认） | 跟随 `GET {NEW_API}/api/status` 的 `email_verification`，上游一开本站即要求 |
| `ON` | 永远要求；上游不配合时注册返回 `503` |
| `OFF` | 永远不要求；上游若已开启，注册会因缺少邮箱与验证码被拒（`503`） |

`AUTO` 下这个标记缓存 1 分钟，所以上游开关后最多 1 分钟生效。上游不可达时沿用上一次的结论，不会因为一次超时
就把要求悄悄丢掉。

几个和上游绑定的硬事实：

- **已绑定的邮箱不会再收到验证码**。New API 的 `/api/verification` 第一件事就是拒绝已占用的地址，
  所以"给一个注册过的用户重发注册码"在上游不存在；本站在这种情况下返回 `409`，而不是假装发送成功；
- **上游开启 Turnstile 时本站的发码代理会失败**。`/api/verification` 挂了 `TurnstileCheck`，
  而本站的请求是服务端发出的，没有挑战令牌。要同时开两家，必须保留 `turnstile_check=false`；
- **上游开启邮箱域名限制时**，只有白名单域名能注册（`EmailDomainRestrictionEnabled` + `EmailDomainWhitelist`），
  本站把这句拒绝原样转成 `503`。

### 图形验证码（防批量注册）

`GET /api/v1/auth/captcha` → `{ captchaId, image, expiresInSeconds }`

- `image` 是 **PNG 的 data URL**（`data:image/png;base64,...`），前端可直接作为 `img.src`；
- **答案不在响应里**，服务端只保存它，并在注册时比对；比对不区分大小写；
- 有效期 `app.captcha.ttl`（默认 5 分钟），**每个验证码只能用一次**：答错、超时、已用过都会作废，
  必须重新获取。前端在提交失败后应当自动换图，否则用户会对着旧图反复输入；
- 该接口按来源 IP 限流（`app.captcha.max-issues-per-ip`，默认 120 次/小时），因为每张图都要渲染一次 PNG；
- `app.captcha.enabled=false` 时该接口返回 **`404`**，注册也不再校验 —— 前端据此判定「这个后端不需要验证码」
  （老 Go 后端同样返回 404，因此两种后端可以共用一套前端）。

验证码错误的注册请求返回 `400`：

```json
{ "status": 400, "error": "Bad Request",
  "message": "Captcha is incorrect or has expired, please refresh and try again" }
```

缺失时 message 为 `Captcha is required`。前端按 `message` 里是否含 `captcha` 归类成中文提示
（`web/src/views/register/index.vue`）。

两个取舍：

- **校验在限流之后**。验证码失败也是一次尝试，若让它绕过计数，一张图就可以被无限次猜；
- **挑战存在进程内**（`ConcurrentHashMap` + TTL + 容量上限 `app.captcha.max-stored`），没有 Redis。
  多实例部署时验证码只能回到签发它的那个实例，负载均衡需要粘性会话；与下面的限流一样属于过渡实现。

### 单 IP 注册限流

同一来源地址在 `app.rate-limit.registration.window` 内最多尝试 `max-attempts-per-ip` 次（默认 **1 次 / 15 秒**），超出返回 `429` 并带 `Retry-After`（秒）：

```json
{ "status": 429, "error": "Too Many Requests",
  "message": "Too many registration attempts from this address, please try again later" }
```

`Retry-After` 是「当前窗口还剩多少秒」，因此上限就是窗口长度 —— 默认配置下**最大 15 秒**，一次短冷却之后即可重试，不存在整小时级别的封禁。

几个刻意的取舍：

- **统计的是尝试次数，不是成功次数**。只统计成功注册会放过用重名或非法名反复试探的脚本；也因此验证码填错、密码不合规、用户名被占用都会消耗一次机会，代价只是等 15 秒；
- **只限注册**。登录、刷新、上传、对话的限流属于 Redis 阶段（规划 §9）；
- **限流键默认用 TCP 对端地址**（`getRemoteAddr()`），**不信任 `X-Forwarded-For`** —— 该头在无代理时会话由客户端控制，用它做键等于形同虚设（改个头就绕过）。只有当服务确实位于会覆写该头的代理之后，才开启 `app.security.trust-forwarded-for`；
- **经 nginx 转发时也能拿到真实访客地址**，靠两段配合：
  1. 前端 nginx 的 `14173` 用 `stream` + `ssl_preread` 分流，这一跳会把对端地址变成 `127.0.0.1`，所以启用了 **PROXY protocol**（stream 侧 `proxy_protocol on`，http 侧 `listen 8085/8443 proxy_protocol` + `set_real_ip_from 127.0.0.1; real_ip_header proxy_protocol;`），把访客地址带进 http 层；
  2. 再把它**覆盖**写入 `X-Forwarded-For`（不是 `$proxy_add_x_forwarded_for`），后端用 `server.forward-headers-strategy=native`（Tomcat `RemoteIpValve`）还原：只在直连对端属于内网段时才解析该头，从右往左取第一个非内网地址。
  配套地，`server.tomcat.remoteip.internal-proxies` **不再沿用默认的全网段**，只信任 Docker 的 `172.16-31` 段与回环地址 —— 默认值含 `192.168/16`，会把内网访客当成代理，导致访客地址被丢掉、全站又落回同一个键。限流键因此是「一个访客一份」，且客户端自带的伪造头在 nginx 处就被覆盖掉；
- 被参数校验拦掉的请求（`400`）不计入配额，因为它根本到不了限流器，也不会消耗正常用户的机会。

> ⚠️ **当前为单进程内存计数**：多实例部署时每个节点各算一份，实际额度是 `上限 × 节点数`。这是接入 Redis 前的过渡实现（规划 P2）。另外固定窗口在跨窗口边界处最多可放过两倍额度，对「拦截脚本批量注册」这一目标可以接受。

> 为什么要限制 72 字节：BCrypt 只取前 72 字节，更长的密码会被静默截断，导致两个不同的密码等价。

## 4. 登录

`POST /api/v1/auth/login`

```json
{ "username": "alice", "password": "correct-horse-battery", "clientType": "web" }
```

成功时更新 `last_login_at` 并签发新会话。

**账号不存在与密码错误返回完全相同的 `401` 提示**，且账号不存在时也会执行一次 BCrypt 比对（对一个占位哈希），使响应时间接近 —— 避免通过响应内容或耗时枚举账号。

登录名与 New API 的用户名是同一个标识：若本站还没有该用户名，但 New API 接受这组凭据，会**即时在本站开户并登录**
（`app.auth.new-api-account-provisioning-enabled`，默认开启）。因此"在 New API 注册过的账号"可以直接登录本站，
前端不需要为这类账号准备单独的入口或提示。名字已被本站某个账号占用时不会被接管 —— 那种情况下仍走本站密码校验。

账号被停用（`status != ACTIVE`）返回 `403`。

**登录不要求邮箱验证码**：本站不发信，也就没有可以拿来比对的码。若需要"必须验证过邮箱才能登录"，打开
`app.auth.email-verification.login-requires-verified-email`，此时只允许 `email_verified=true` 的账号登录，
其余返回 `403`：

```json
{ "status": 403, "message": "账号邮箱尚未验证，无法登录；请先完成邮箱验证" }
```

两个后果要先知道：

- 该开关之前的存量账号 `email_verified` 全是 `false`，直接打开会**全员锁死**。先按
  `docs/newapi-deployment-checklist.md` §5 补齐数据；
- `app.auth.new-api-account-provisioning-enabled` 那条「New API 有号、本站没号就即时开户」的路径
  在开关打开后**一律拒绝**：它拿不到任何邮箱验证证明，开出来的账号也永远登不进来。这时用户必须先走注册流程。

## 5. 刷新令牌

`POST /api/v1/auth/refresh`

```json
{ "refreshToken": "<登录时返回的 refreshToken>" }
```

每次刷新都会**轮换**：旧会话被撤销，签发新的令牌对。

**重放检测**：如果被撤销（已轮换过）的 refresh token 再次出现，视为该令牌已泄漏 —— 攻击者与真实客户端同时持有它，此时**撤销该账号的全部活跃会话**并返回 `401`，用户需要重新登录。

```
登录 → refreshToken A (会话 s1)
刷新 A → s1 撤销，返回 refreshToken B (会话 s2)
再刷新 A → 401，且 s1/s2 全部撤销
```

## 6. 登出与当前账号

`POST /api/v1/auth/logout` → `204`

撤销当前 access token 所属会话；之后该 access token（虽然签名仍有效）与配套 refresh token 都不再可用。

`GET /api/v1/auth/me` → 当前账号信息，需 `Authorization: Bearer <accessToken>`。

```json
{
  "id": 3,
  "siteId": 1,
  "username": "alice",
  "nickname": "Alice",
  "email": null,
  "phone": null,
  "avatarUrl": null,
  "status": "ACTIVE",
  "vipExpiresAt": null,
  "createdAt": "2026-10-14T18:12:44.486496"
}
```

## 7. 令牌响应

```json
{
  "tokenType": "Bearer",
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresIn": 1800,
  "refreshToken": "w1VPpGk55_rAzkK5QGxFcSKFWLriw1fFm-l9HFGzDbg",
  "refreshExpiresIn": 2592000,
  "user": { "id": 3, "username": "alice", "...": "..." }
}
```

`user.emailVerified` 表示这个地址是否经上游验证过。`PUT /api/v1/auth/profile` 改动 `email` 时会把它清回
`false` —— 自己填的地址不是证明过的地址，否则改这一个字段就能把别人的邮箱"认领"成已验证。

## 8. 身份解析

请求接口只接受 `Authorization: Bearer <accessToken>`。`CurrentUserResolver.require(HttpServletRequest)`
是严格解析，实际委托给 `requireAuthenticated` / `requireClaims`：

- 未携带 Bearer、Bearer 值为空、令牌格式错误、验签失败、过期或会话已撤销，统一返回 `401`；
- 不会降级到 `X-User-Id`，也不会回退到 `app.security.default-username` 对应的演示账号；
- 账号资源必须按验签后的账号 ID 查询，不能信任客户端自报的用户 ID。

`app.security.trust-user-header` 只保留给历史非请求解析路径；HTTP 请求接口不使用该开关。

## 9. 安全设计要点

| 关注点 | 做法 |
|---|---|
| 口令存储 | BCrypt，cost 12（`app.auth.bcrypt-strength`） |
| 令牌存储 | 只存 refresh token 的 SHA-256；access token 不落库 |
| 算法混淆 | 验签**之后**才读 `alg` 并要求必须为 HS256，`alg: none` / RS256 一律拒绝 |
| 签名比较 | `MessageDigest.isEqual`，比较耗时与匹配前缀长度无关 |
| 签发方校验 | 校验 `iss`，拒绝其他服务签发的令牌 |
| 密钥来源 | 仓库不提供默认签发密钥；`AUTH_JWT_SECRET` 为空或 HS256 密钥 < 32 字节时**启动失败** |
| 会话即时失效 | 每次请求校验会话状态，登出/停用立即生效 |
| 账号枚举 | 未知账号与密码错误同文案 + 时序均衡 |
| 会话标识 | `user_identities.provider_subject` 以站点命名空间（`{siteKey}:{username}`），避免全局唯一键把两个站点的同名用户判为冲突 |
| 审计字段 | 会话记录 `user_agent` / `ip_address`（截断存储）；`X-Forwarded-For` 仅作审计，不参与授权判断 |

## 10. 错误语义

| 状态码 | 触发条件 |
|---|---|
| `400` | 参数校验失败（登录名格式、密码长度、密码超 72 字节）；上游认为邮箱验证码不正确或已过期 |
| `401` | 登录名或密码错误；未携带或携带无效 access token；refresh token 未知/过期/已轮换 |
| `403` | 账号已停用；`login-requires-verified-email` 打开时账号邮箱未验证 |
| `404` | 请求所属站点不存在；上游未启用邮箱验证（`POST /auth/email-code`） |
| `409` | 登录名已被占用；邮箱已被其他账号占用 |
| `429` | 注册请求超过单地址配额，或发码超过 IP / 邮箱配额，带 `Retry-After` |
| `503` | 邮箱验证已开启但上游不可用、SMTP 未配置或拒绝发信 |

## 11. 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `app.auth.jwt-secret` | 空 | **每个环境都必须通过 `AUTH_JWT_SECRET` 提供至少 32 字节的密钥**，否则服务拒绝启动 |
| `app.auth.issuer` | `backend-service` | 写入并校验 `iss` |
| `app.auth.access-token-ttl` | `30m` | access token 有效期 |
| `app.auth.refresh-token-ttl` | `30d` | refresh token / 会话有效期 |
| `app.auth.bcrypt-strength` | `12` | BCrypt cost，仅测试建议调低 |
| `app.auth.email-verification.register-mode` | `AUTO` | 注册是否必须带上游验证过的邮箱：`AUTO` 跟随上游 / `ON` 总是 / `OFF` 从不 |
| `app.auth.email-verification.login-requires-verified-email` | `false` | 登录是否要求 `email_verified=true`；打开前必须补齐存量账号 |
| `app.rate-limit.email-code.max-per-ip` | `10` / 10 分钟 | 单来源地址请求验证码的上限 |
| `app.rate-limit.email-code.max-per-email` | `3` / 10 分钟 | 同一邮箱请求验证码的上限 |
| `app.security.trust-user-header` | `false` | 仅保留给历史非请求解析路径；请求接口始终忽略 `X-User-Id` |
| `app.security.trust-forwarded-for` | `false` | 是否信任 `X-Forwarded-For`；**同时影响审计 IP 与注册限流键**，只有确实位于可信代理后才可开启（现在改由 `server.forward-headers-strategy=native` 还原地址，本项保持关闭） |
| `server.forward-headers-strategy` | `native` | 由 Tomcat `RemoteIpValve` 还原客户端地址：仅当直连对端是内网段时解析 `X-Forwarded-For`，从右往左取第一个非内网地址 |
| `server.tomcat.remoteip.internal-proxies` | `127.0.0.0/8` + `172.16.0.0/12` | 只把这些网段当作可信代理；默认值还包含 `192.168/16`，会让内网访客被误判成代理。换宿主机网段需同步修改 |
| `app.rate-limit.enabled` | `true` | 总开关，仅测试/本地调试建议关闭 |
| `app.rate-limit.max-tracked-keys` | `100000` | 限流键上限，超出后先淘汰已关闭窗口的计数 |
| `app.rate-limit.registration.max-attempts-per-ip` | `1` | 单地址注册尝试上限（每窗口） |
| `app.rate-limit.registration.window` | `15s` | 注册计数窗口，即最长等待秒数 |

## 12. 测试

| 用例类 | 覆盖 |
|---|---|
| `TokenServiceTest` | 签发与校验、篡改载荷/签名、过期、`alg:none`、声明 RS256 但签名正确、异密钥、异签发方、垃圾输入、弱密钥拒绝、refresh 随机性、哈希形态 |
| `CurrentUserResolverTest` | Bearer 解析、请求解析忽略开发头、缺失/非法令牌 401、Bearer 大小写、历史兼容路径的站点校验 |
| `AuthControllerTest` | 注册与落库（BCrypt 前缀、identity 命名空间）、重复名 409、弱密码 400、非法用户名 400、登录成功、错误密码与未知账号同响应、停用账号 403、`/me` 401 与成功、令牌轮换、**重放撤销全部会话**、未知 refresh 401、登出即时失效、OpenAPI 路径 |
| `InMemoryRateLimiterTest` | 额度内放行、超额拒绝并给出重试秒数、窗口滚动后恢复、键之间独立、内存有界、reset |
| `RegistrationRateLimitTest` | 第 3 次注册 429 且带 `Retry-After`、**轮换 `X-Forwarded-For` 无法绕过**、登录不受限、400 不消耗配额 |

```powershell
.\mvnw.cmd test
```

## 13. 尚未覆盖（后续阶段）

- **验证码登录**（短信/邮箱）：需要投递通道；表 `verification_codes` 已具备 `purpose` / `code_hash` / `consumed_at`，实现时须加限次、单次消费、用途绑定与过期清理；
- 微信 / 邮箱等第三方身份（`user_identities.provider` 已预留）；
- **登录 / 刷新 / 上传 / 对话的限流**：注册已有限流（见 §3），其余仍待补，规划 §9 要求全部接入，统一走 Redis；
- 暴力破解防护的账号维度锁定（当前只有地址维度）；
- 审计日志（登录/登出/封禁/改密）与敏感事件通知；
- 登录设备列表与「踢出其他设备」；
- RBAC 与管理端权限；
- HttpOnly/Secure/SameSite Cookie 模式（当前仅 Bearer 模式，规划 §9 提到两种模式按部署选择）；
- 密码重置与改密。
