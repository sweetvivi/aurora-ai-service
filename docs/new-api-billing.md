# New API 用户令牌与计费

> 状态：已移除 New API 全局管理凭据。
>
> 公共模型和价格默认匿名读取；New API 把模型广场改为「需登录」后，由同步专用只读账号
> 登录后读取（见 2.1）。账号、Token、余额和模型调用只使用当前用户自己的
> New API 登录态、PAT 或 Relay Token。New API 仍是余额和扣费的唯一权威来源。

## 1. 依赖边界

本服务对 New API 只有三类访问：

| 访问类型 | 凭据 | 用途 |
|---|---|---|
| 匿名公开读取 | 无 | 公开模型、公开分组、公开价格和模型可见状态（广场默认公开） |
| 目录同步账号（可选） | `NEW_API_CATALOG_USERNAME` / `NEW_API_CATALOG_PASSWORD` 换取的短期 login token | 广场开启「需登录」时读取模型和价格，不写任何数据 |
| 当前用户登录态 | 用户自己的用户名和密码换取的短期 login token | 当前用户的 PAT 管理、敏感操作证明和额度兑换 |
| 当前用户 Token | 用户自己的 PAT 或 Relay Token | 当前用户的 Relay Token 管理、额度查询、消费日志和模型调用 |

服务端不读取、不保存、也不接受 New API 管理员账号或全局管理令牌。New API 用户、渠道、
分组、价格、余额和兑换码均由 New API 管理员在其控制台维护。

## 2. New API 接口清单

所有接口都以 `NEW_API_BASE_URL` 为根地址，且该地址不带 `/v1`。

### 2.1 模型广场（默认匿名，可要求登录）

| 接口 | 用途 |
|---|---|
| `GET /api/pricing` | 读取模型、分组和价格 |
| `GET /api/user/login/encryption-key` | 获取登录密码加密公钥 |
| `POST /api/user/login` | 使用用户自己的用户名和密码换取 login token |

`/api/pricing` 由 New API 的 `HeaderNavModules.pricing` 控制：默认 `requireAuth=false`，
请求不带 `Authorization`；一旦管理员把它设成 `requireAuth=true`，匿名请求返回 401，同步必须
改用同步账号的 login token 作为 `Authorization: Bearer`。配置了
`NEW_API_CATALOG_USERNAME` / `NEW_API_CATALOG_PASSWORD` 时后端先登录再读广场，令牌缓存在内存、
被 401 或 403 拒绝时自动重登一次；未配置则保持匿名请求。

⚠️ 广场返回的是**该账号可用分组**里的模型：New API 用调用者分组过滤 `enable_groups`，
并用该分组倍率换算价格。同步账号在 `default` 分组就看不到仅 `vip` 分组可用的模型，
换一个分组账号就会换一份目录和价格。

同名模型出现在多个分组时，每个价格字段取遍历顺序上第一个非空值，`0` 表示有效免费价，
`null` 表示该字段未公开。

### 2.2 当前用户 login token

| 接口 | 用途 |
|---|---|
| `GET /api/user/access_tokens` | 列出当前用户的 PAT |
| `POST /api/user/access_tokens` | 创建当前用户的 PAT |
| `PATCH /api/user/access_tokens/{id}` | 更新当前用户的 PAT |
| `DELETE /api/user/access_tokens/{id}` | 删除当前用户的 PAT |
| `POST /api/verify` | 为敏感操作生成安全证明 |
| `POST /api/user/topup` | 当前用户兑换额度 |

### 2.3 当前用户 PAT

| 接口 | 用途 |
|---|---|
| `GET /api/token/search` | 查询当前用户的 Relay Token |
| `GET /api/token/` | 校验当前用户的 PAT |
| `POST /api/token/` | 创建当前用户的 Relay Token |
| `PUT /api/token/` | 更新当前用户的 Relay Token |
| `DELETE /api/token/{id}` | 删除当前用户的 Relay Token |
| `POST /api/token/{id}/key` | 读取当前用户 Relay Token 的明文 |
| `GET /api/log/self` | 读取当前用户的流水日志（**不带 `type` 过滤**：扣费 `type=2` 与失败任务退款 `type=6` 要一起取，否则用户只看到扣费看不到退回） |

### 2.4 当前用户 Relay Token

| 接口 | 用途 |
|---|---|
| `GET /api/usage/token/` | 查询当前用户的额度和 Relay Token 状态 |

## 3. 用户首次开通

用户首次使用 AI 功能时，服务端使用登录本站时留在内存中的密码登录 New API
（`SessionPasswordVault`，不落库；服务重启后需要重新登录一次）：

1. 调用 `POST /api/user/login`，取得该用户自己的短期 login token。
2. 调用 `POST /api/verify`，用当前密码生成限定 scope 的 PAT。
3. 调用 `POST /api/user/access_tokens` 创建 PAT。
4. 用 PAT 查询、创建或修复该用户自己的 Relay Token。
5. 用 `POST /api/token/{id}/key` 读取 Relay Token 明文。
6. 用 `NEW_API_ENCRYPTION_KEY` 加密保存 PAT 和 Relay Token。
7. 后续 AI 请求只使用该用户自己的 Relay Token。

Relay Token 配置为 `allow_user_quota_query=true`，并使用 `NEW_API_GROUP`。同一平台账号不会并行
创建多个 Relay Token；并发首次开通通过数据库租约串行化，租约占用期间返回 `503`。

绑定和额度快照保存在 `user_gateway_tokens`：

- `newapi_user_id`：当前用户的 New API 用户 ID；
- `newapi_pat_id` 与加密后的 PAT：只用于管理当前用户的 Relay Token 和读取消费日志；
- `newapi_token_id` 与加密后的 Relay Token：用于模型调用和额度查询；
- `total_*`、`unlimited_quota`、`model_limits`、`last_sync_at`：展示和审计快照，不是本地余额。

PAT 只在 New API 明确返回 `401` 或 `403` 时重建。网络异常、超时和 `5xx` 不会被当作凭据失效。

## 4. 账号与密码

本站注册只创建本地账号，不会调用管理员接口创建 New API 用户。要使用模型调用、余额查询、
兑换或消费日志，用户必须已经拥有可登录的 New API 账号。

`AUTH_NEW_API_ACCOUNT_PROVISIONING_ENABLED=true` 控制即时开户：

- 登录名在本站不存在时，服务端尝试用同一组用户名和密码登录 New API；
- New API 接受后，才创建对应的本地账号并绑定该 New API 用户；
- 关闭该开关后，只有本站已有账号可以登录；
- New API 上的同名账号不能接管已存在的本地同名账号。

生产环境应关闭 New API 公开注册，或关闭 `AUTH_NEW_API_ACCOUNT_PROVISIONING_ENABLED`。否则 New API
的公开注册入口会同时成为本站新账号入口。

本地账号和 New API 账号的密码必须分别维护：

- 本地登录密码只保存 Argon2id 哈希，数据库里没有可逆的密码副本（V76 已删除相关列）；
- 注册时用同一个明文密码调用 New API 的公开注册端点 `POST /api/user/register`，两边各自哈希；
- 登录/改密时提交的明文只留在进程内存，供以该用户身份登录 New API 使用；
- 本站不会修改 New API 远端密码；在 New API 控制台改密后，必须在本站重新登录一次，
  内存中的副本才会更新，否则用户级 New API 调用会失败。

`NEW_API_ENCRYPTION_KEY` 只保护用户自己的 PAT 和 Relay Token，不是管理凭据。Base64 解码后
必须是 32 字节。更换该密钥会导致已有的 PAT 和 Relay Token 无法解密。

## 5. 请求链路

所有用户 AI 入口共用 `UserRelayCredentialResolver`：

1. 解密当前用户的 Relay Token。
2. 在缓存过期时调用 `GET /api/usage/token/`。
3. 读取 `data.user_quota.total_available`。
4. 余额大于 `0` 时，把 Relay Token 作为 Bearer 凭据调用 `LLM_BASE_URL`。
5. 余额小于等于 `0` 时，在模型调用前返回 `402`。

当前覆盖：

| 功能 | 入口 | 计费凭据 |
|---|---|---|
| 普通对话（同步/流式） | `/api/v1/chat/completions`、`/api/v1/chat/stream` | 当前用户 Relay Token |
| Agent 对话 | `/api/v1/agents/**` 对话入口 | 当前用户 Relay Token |
| 图片生成 | `/api/v1/images/generations` | 当前用户 Relay Token |
| 智能修图 | `/api/v1/smart-editing/**` | 当前用户 Relay Token |
| AI Canvas | `/api/v1/ai-canvas/**` 中的模型调用 | 当前用户 Relay Token |
| 视频生成 | `/api/v1/videos/**` | 当前用户 Relay Token |
| 论文全文 | `/api/v1/thesis/full-text/**` | 当前用户 Relay Token |

文本、图片、视频与论文调用都走当前用户 Relay Token，不再回退全局 Key、供应商 Key 或
`APP_LLM_API_KEY`。New API 的 `NEW_API_GROUP` 必须同时允许公开目录中启用的文本、图像与视频模型。

### 5.1 分组与请求路由

请求走哪个分组**不是**本站能临时决定的：New API 用 Relay Token 上的 `group` 字段选渠道，
模型还必须在该分组里启用（`enable_groups` 含该分组），否则表现为「模型不存在 / 无可用渠道」。
本站在 `POST /api/token/` 建 Token 时把 `group` 写成 `NEW_API_GROUP`，因此**一套部署下所有用户
都在同一个分组**，切换分组只能改配置重启，且影响所有人。

要让用户用到其他分组的模型，只有四条路：

| 方案 | 做法 | 代价 |
|---|---|---|
| 改全局分组 | 把 `NEW_API_GROUP` 改成目标分组 | 最简单；所有人一起搬，模型集合和倍率同时变化 |
| 上游改用户分组 | 在 New API 控制台把该用户 `group` 设为目标分组 | 一对一，不用改本站；但本站建 Token 时仍写 `NEW_API_GROUP`，需同时对齐 |
| 按模型签发多把 Token | 本站按模型的 `enable_groups` 各建一把 Relay Token，调用时选模型所属分组那把 | 能力最全（同一用户可跨分组）；需要目录存 `enable_groups`、开通逻辑支持 1→N Token、路由按模型挑 Token，改动最大 |
| 放开可用分组 | 上游把目标分组加入该用户的 `UserUsableGroups` 特殊规则 | 只是让 Token 能选该分组；本站目前不接收分组参数，仍要配合前两条 |

注意两点：

- 广场同步拿到的模型集合受**同步账号**的分组限制（见 2.1），和用户 Token 的分组是两件事；
  两者不一致时会出现「前台能看到、调用报模型不存在」。
- `/api/user/self/groups` 返回当前用户可用的分组，可作为「该用户到底能不能用某分组」的判据。

#### 5.1.1 上游的 auto 分组：接口收、请求拒

New API rc.47 起有「自动分组」：把 Token 的 `group` 写成保留值 `auto`，再用 `auto_groups`
给出候选分组，由上游按顺序找有渠道的分组，并按实际命中分组计费。这条能力**不能依赖**：

| 事实 | 实测（api.demo-relay.online，账号 jmumumu，2026-11-11） |
|---|---|
| `GET /api/token/auto-groups` | 200，`max_count = 5`，`groups = ["default"]`（站点全局 Auto 列表只有 default） |
| `POST /api/token/` `group=auto` + 5 个 `auto_groups` | 成功，`auto_groups` 原样落库 |
| 超过 5 个 | `A token can select at most 5 Auto groups` |
| 用该 Token 调模型 | **403 `No permission to access group auto`**，开 `cross_group_retry` 无效 |
| 普通 `group=kimi` Token 调 `kimi-k2.5` | 通过选渠道，只报 `insufficient_user_quota`（余额 0） |

结论：该部署在请求入口就把 `auto` 拒了（上游源码 rc.47/main 里没有这句文案，属站点侧改动），
而且上限 5 个也覆盖不了它 31 个可选分组。**跨分组只能走「按模型签发多把普通 Token」**，
每把 Token 的 `group` 填模型 `enable_groups` 里的一个真实分组名，不要填 `auto`。
`GetMaxUserTokens` 默认 1000，按分组建不会撞上限。

#### 5.1.2 本站实现：按分组懒建令牌

已落地（V108 `newapi_group_tokens`）。规则：

| 场景 | 用哪把钥匙 |
|---|---|
| 模型在 `NEW_API_GROUP`（默认 `default`）里可用 | **账号令牌**，与升级前完全一致，不多建任何 Token |
| 模型只在别的分组里可用 | 该用户在该分组上的令牌，**第一次用到时现建** |
| `app.new-api.enabled=false`（本地开发） | `app.llm.api-key` / `image-api-key`，与之前一致 |

选组顺序由 `ModelGroupRouter` 决定：先看 `NEW_API_GROUP`，模型支持它就用它（保住历史价格与渠道），
否则按模型自己的 `enable_groups` 顺序取，并跳过 `newapi_groups` 里已标记 `usable=false` 的分组。
`upstream_groups` 为空（未登记）或分组表尚未同步时不做判断，直接沿用账号令牌。

懒建过程：

1. 先取账号令牌（这一步顺带把用户 PAT 建好），拿到 PAT 与远端 user id；
2. `GET /api/user/self/groups` 确认该账号确实能用这个分组（结果缓存 5 分钟，读失败则放行）——
   rc.47 的 `POST /api/token/` 不校验非 auto 分组的可见性，不先问就会建出一把调用时才 403 的令牌；
3. `POST /api/token/`，`name = hub-{站点}-u{用户}-g{分组名 SHA-256 前 8 位}`，`group` 填真实分组名；
4. `POST /api/token/{id}/key` 取明文，AES-GCM 加密后落 `newapi_group_tokens`，之后直接复用。

令牌名用分组哈希而不是分组名本身：New API 的令牌名上限 50 字符，而分组名可能到 64 字符且是中文。
哈希同时保证**幂等**——本地行丢了也能按名字把上游那把钥匙找回来，不会每次调用都新建一把。

失败语义与账号令牌一致：上游拒绝 → `LlmInvocationException`；正在开通中 → 503 让人重试；
**绝不回落到别的分组的钥匙**，那会把请求悄悄发到错误的渠道上并按另一份价格计费。

并发与退避（两种状态不能混为一谈）：

| 状态 | 含义 | 行为 |
|---|---|---|
| `BUSY` | 另一个请求正持有租约在开通（`NEW_API_PROVISIONING_LEASE`，默认 2 分钟） | 先等最多 15 × 200ms 再复查；对方建好就直接复用它的钥匙，超时才 503 |
| `BACKOFF` | 上一次开通失败，`next_retry_at` 还没到（`NEW_API_RETRY_DELAY`，默认 30 秒） | 立即失败，并把 `last_error` 原文带进日志与异常 |

这两者以前都报 `is being provisioned`，于是「账号没有该分组 / 密码不在会话里 / 上游拒绝建令牌」
这类**永久性失败**被伪装成临时故障，前端提示「稍后重试」，用户就一直重试，日志里也看不到真正原因。
排查时先看这张表：

```sql
SELECT user_id, group_key, status, attempt, next_retry_at, lease_until, last_error
FROM newapi_group_tokens WHERE group_key = '<分组名>';
```

`attempt` 持续增长且 `last_error` 非空 → 退避中的真实失败；`attempt=0` 且 `lease_until` 在未来 → 并发竞态。

已接入的调用点：对话（含回退链逐候选）、智能体对话（按请求的模型）、生图（含画布、修图文生图、
修图编辑）、视频提交、文档问答/文档动作、视频提示词翻译、商务文案（逐候选）。

##### PAT 需要 `profile:read`

新版 New API 的访问令牌是**分权限**的，权限清单见 `GET /api/user/access_tokens/scopes`：

```
profile -> read, write        api_key -> read, write, reveal      usage -> read
```

`GET /api/user/self/groups` 属于 `profile:read`（清单描述为「View your profile, groups,
and available models」）。缺了它，分组懒建在读可用分组这一步得到
`403 The access token lacks the required permission: profile:read`，令牌永远建不出来；
而旧实现又把 403 当「认证失败」直接抛出，再叠加 30 秒退避，前端只会看到
`The relay token for group 'X' is being provisioned; retry shortly`。

因此 `PAT_SCOPES` 必须是 `profile:read + api_key:read/write/reveal + usage:read`，
且改动它要**同时升 `CURRENT_PAT_SCOPE_VERSION`** —— 已存在的 PAT 靠这个版本号触发
`PATCH /api/user/access_tokens/{id}` 补权限（`requireRelayBase` 与用量查询都会触发）。
另外 `usableGroups` 只把 **401** 当致命错误，403 一律当「查不到」放行：否则一个缺权限
的旧 PAT 会让分组懒建永久失败。

仍使用账号令牌的地方，都是「拿不到模型」的路径：视频任务轮询（任务表不存模型，且状态查询
不按模型分渠道）、PPT 生成、画布助手的付费前置检查。这些路径请求的模型若只在非默认分组可用，
会得到上游的「该分组无可用渠道」——需要时再逐个接入。

## 6. 查询当前账户用量

`GET /api/v1/usage/token`

需要本站正式登录：

```http
Authorization: Bearer <accessToken>
```

默认读取**本地快照**（`NEW_API_USAGE_CACHE_TTL`，默认 3 分钟），避免每次页面加载都去查
New API 的关键限额接口。用户在扣费 / 退款这类「刚刚动过钱」的时刻可以显式要求对账：

```http
GET /api/v1/usage/token?refresh=true
```

`refresh=true` 仍受 `NEW_API_USAGE_FORCE_REFRESH_INTERVAL`（默认 10 秒）约束：快照必须至少
这么旧才会真正打上游，防止一个自动刷新的页面把 20 次 / 20 分钟的额度吃光。前端在
「提交视频任务、任务失败退款、生图完成」这些节点带上它。

响应示例：

```json
{
  "maskedKey": "sk-abc...wxyz",
  "tokenName": "hub-1-u42",
  "totalGranted": 1000000,
  "totalUsed": 120000,
  "totalAvailable": 880000,
  "totalGrantedCny": 14.6,
  "totalUsedCny": 1.75,
  "totalAvailableCny": 12.85,
  "quotaPerUnit": 500000,
  "usdExchangeRate": 7.3,
  "unlimitedQuota": true,
  "modelLimits": {},
  "modelLimitsEnabled": false,
  "expiresAt": null,
  "lastSyncedAt": "2026-10-31T03:20:15Z"
}
```

`totalGranted`、`totalUsed`、`totalAvailable` 统一表示 New API 用户额度
（`data.user_quota`），不是 Relay Token 自己的额度，单位是整数 quota。`totalGranted` 由
`user_quota.total_used + user_quota.total_available` 计算。

`quotaPerUnit` 与 `usdExchangeRate` 是后端当前生效的 `NEW_API_QUOTA_PER_UNIT` /
`NEW_API_USD_EXCHANGE_RATE` 原样回显，供前端换算，不涉及任何额外的上游请求。
这样前端可以直接用 `totalAvailable / quotaPerUnit` 得到 **与 New API 后台
`$` 数字完全一致**的美元金额（后台展示的就是 `quota / quota_per_unit`），
而部署侧调整常量后也无需同步发版前端。

> 约定：前台余额**默认按美元展示**。人民币金额优先取本接口的 `*Cny` 字段（后端已按
> `HALF_UP` 保留 2 位），缺失时才退回「美元 × `usdExchangeRate`」自己算，
> 以免两端各自四舍五入后出现分位差异。

额度快照默认缓存 `NEW_API_USAGE_CACHE_TTL=3m`，避免每次 AI 调用和余额面板轮询都消耗 New API
critical 限流。余额为零的快照会重新查询，因此充值后无需等待完整缓存周期。余额为零仍返回 `200`；
是否阻止 AI 调用由业务入口的余额检查负责。

CNY 展示金额只使用本地常量换算：

- `NEW_API_QUOTA_PER_UNIT`，默认 `500000`；
- `NEW_API_USD_EXCHANGE_RATE`，默认 `7.3`。

运行时不读取 New API 的管理状态接口来获取汇率或额度单位，只把上述两个常量回显给前端
（见上文的 `quotaPerUnit` / `usdExchangeRate`）。

## 7. 错误语义

| 状态码 | 触发条件 |
|---|---|
| `401` | 本站未登录、访问令牌无效，或 New API 在用户登录 / PAT 校验阶段明确拒绝凭据 |
| `402` | `user_quota.total_available <= 0`，在模型调用前拒绝 |
| `503` | New API 必填配置缺失、登录态中没有可用密码（需重新登录）、Relay Token 正在开通、额度查询超时或响应非法 |
| `502` | 余额检查通过后，模型网关调用本身失败 |

New API 拒绝内存中的密码时，错误文案会要求用户在本站重新登录，登录成功后内存副本即为最新密码。
服务端不会尝试修改 New API 远端密码。

## 8. 模型目录与价格排序

目录同步只调用 `GET /api/pricing`（默认匿名，广场要求登录时用同步账号的 login token）：

- 新出现的公开模型进入本站目录；
- 广场仍公开的模型保留运营侧展示名、状态和排序；
- 从广场消失的模型不删除历史行，只标记 `upstreamAvailable=false`，前台不再展示；
- 空响应或广场访问失败（含同步账号被拒）时保留上一份成功快照；
- 不读取渠道私有模型，不探测渠道 Key，不写回渠道模型列表；
- 不写入或继承 New API 定价，渠道状态也不再作为模型可用状态来源。

价格同样只来自广场的 `pricing`。同名模型聚合广场返回的各分组价格，每个字段取第一个非空值。
没有任何可用价格的模型排在已定价模型之后。价格快照缓存 5 分钟，广场暂时失败时继续使用
上一份快照。

⚠️ 分组维度在同步时被拍平：`llm_models` 只存模型本身，不存 `enable_groups`。因此前台展示的
是「同步账号可见分组」的并集，而用户实际能用哪些分组，取决于其 Relay Token 上的 `group`
（见 5.1「分组与请求路由」）。

## 9. 部署配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `NEW_API_BASE_URL` | `https://api.example.com` | New API 根地址，不带 `/v1` |
| `NEW_API_ENCRYPTION_KEY` | 空 | Base64 编码的 32 字节 AES 密钥，仅用于加密用户自己的 PAT 和 Relay Token |
| `NEW_API_TOKEN_NAME_PREFIX` | `hub` | PAT 与 Relay Token 名称前缀 |
| `NEW_API_GROUP` | `default` | 创建 Relay Token 时使用的 New API 分组 |
| `NEW_API_LOGIN_TIMEOUT` | `15s` | 用户登录 New API 超时 |
| `NEW_API_USER_ACCESS_TOKEN_TIMEOUT` | `15s` | PAT 管理请求超时 |
| `NEW_API_TOKEN_MANAGEMENT_TIMEOUT` | `15s` | Relay Token 管理和兑换请求超时 |
| `NEW_API_CATALOG_TIMEOUT` | `15s` | 模型广场请求超时 |
| `NEW_API_CATALOG_USERNAME` | 空 | 可选：广场要求登录时同步专用的只读账号；留空保持匿名读取 |
| `NEW_API_CATALOG_PASSWORD` | 空 | 上项账号的密码（需要明文换取 login token，不落日志） |
| `NEW_API_USAGE_TIMEOUT` | `15s` | 余额和消费日志查询超时 |
| `NEW_API_USAGE_CACHE_TTL` | `3m` | 额度快照有效期，`0` 表示每次实时查询 |
| `NEW_API_USAGE_FORCE_REFRESH_INTERVAL` | `10s` | `?refresh=true` 穿透快照的最小间隔，防止刷新循环吃光上游限额 |
| `NEW_API_PROVISIONING_LEASE` | `2m` | 首次开通 Token 的跨实例租约 |
| `NEW_API_RETRY_DELAY` | `30s` | 开通失败后的重试间隔 |
| `NEW_API_QUOTA_PER_UNIT` | `500000` | 每美元对应的 New API quota |
| `NEW_API_USD_EXCHANGE_RATE` | `7.3` | 钱包展示使用的 USD/CNY 汇率 |
| `AUTH_NEW_API_ACCOUNT_PROVISIONING_ENABLED` | `true` | 是否允许已能登录 New API 的同名用户首次登录本站时建立本地账号 |
| `LLM_BASE_URL` | `https://api.example.com/v1` | 用户 Relay Token 调用的 OpenAI 兼容入口 |

以下配置已移除，不应继续部署：

- New API 管理员账号、密码或访问令牌；
- 管理令牌 scope 配置；
- 密码同步和反向密码权威开关；
- 渠道模型回写和定价写入开关。

`NEW_API_ENCRYPTION_KEY` 不得暴露给前端，也不应写入公开日志。

## 10. 上线检查

1. New API 模型广场可访问（匿名，或已配置同步账号），并发布需要展示的模型和价格。
2. `NEW_API_GROUP` 对应用户分组存在，并覆盖本站开放的文本、图像及 Canvas 模型。
3. `NEW_API_BASE_URL` 与 `LLM_BASE_URL` 指向同一套 New API 部署。
4. `NEW_API_ENCRYPTION_KEY` 由正式密钥管理系统注入，迁移时保持不变。
5. New API 公开注册已关闭，或本站即时开户已关闭。
6. `TRUST_USER_HEADER=false`，业务入口使用本站 Bearer 登录身份。
7. 用真实账号验证 New API 登录、Relay Token 开通、余额查询、消费日志和模型调用。
8. 验证余额为零、New API 超时、PAT 失效和流式断连场景。
9. 运行 `tools/newapi-preflight.sh`，确认不需要任何 New API 管理凭据。

## 11. 限流

New API 的 `/api/user/login`、Token 管理和 `/api/usage/token/` 通常经过 global critical 限流。
平台后端可能从同一出口 IP 为所有用户发起这些请求，默认的 `20 次 / 20 分钟` 不适合集中部署。

内网部署应根据登录、首次开通、额度刷新和兑换并发调整：

```dotenv
CRITICAL_RATE_LIMIT_ENABLE=true
CRITICAL_RATE_LIMIT=10000
CRITICAL_RATE_LIMIT_DURATION=60
```

多实例后端共用 NAT 出口时，需要按所有实例的总请求量计算。若 New API 仅通过可信内网或回环地址
暴露，也可以关闭 critical 限流，但必须确保 New API 管理端和登录接口不会被不可信网络直接访问。
