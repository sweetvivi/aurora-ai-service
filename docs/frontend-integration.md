# 前端对接说明

> 这份文档回答「前端要的接口」与本服务实际提供的接口之间的差异。
>
> **约定：接口路径与响应结构沿用本服务自己的命名规则**，不为迁就某一版前端而复制别名或信封格式。
> 需要的能力已经补齐；纯路径/协议差异由前端适配。

## 1. 能力对照

| 前端期望 | 本服务提供 | 状态 |
|---|---|---|
| `GET /chat/sessions` | `GET /api/v1/conversations` | 路径不同；返回**分页对象**（`{items,total,page,size}`），一次取完用 `?size=100` |
| `POST /chat/sessions` | `POST /api/v1/conversations` | **已补齐**（显式建会话，无需先发一次对话） |
| `POST /chat/sessions/{id}/messages` | `POST /api/v1/conversations/{id}/messages` | **已补齐**（只落库，不调用模型） |
| `PUT /chat/sessions/{id}` | `PATCH /api/v1/conversations/{id}` | 路径与方法都不同（RESTful 语义：改标题用 PATCH） |
| `POST /chat/sessions/batch-delete` | `POST /api/v1/conversations/batch-delete` | **已补齐**（一次最多 100 个，返回 `requested/deleted/skipped`） |
| `GET /chat/models` | `GET /api/v1/models` | 路径不同；返回 `{groups, models}`，另有 `GET /api/v1/models/{modelKey}`；`?type=CHAT` / `?type=IMAGE` 可按类型过滤 |
| `GET /chat/model-families` | `GET /api/v1/models/families` | **已补齐**（按厂商归组，不必自行组装）；同样支持 `?type=CHAT` / `?type=IMAGE` |
| `POST /chat/upload` | `POST /api/v1/files` | 路径不同；URL 字段规则见 `docs/file-api.md` |
| `POST /chat/images/generations` | `POST /api/v1/images/generations` | **已补齐**（结果落为本平台附件，返回 `/api/v1/files/{id}/content`） |
| 流式：裸 `data:` | `POST /api/v1/chat/stream` | **协议不同**：具名 SSE 事件，见 §3 |
| `{code, message, data}` 信封 | 裸 DTO / 错误用 `ApiError` | 成功判断逻辑要改，见 §4 |

## 2. 需要前端适配的五点

1. **会话列表是分页对象**，不是数组。`GET /api/v1/conversations?size=100` 可一次取回（上限 100）。
2. **建会话与发消息已拆开**：先 `POST /api/v1/conversations` 拿到 `id`，再 `POST /api/v1/conversations/{id}/messages` 写消息；需要模型回答时用 `POST /api/v1/chat/stream` 并带上 `conversationId`。也可以继续沿用旧习惯——不传 `conversationId` 时对话接口会自动建会话。
3. **流式协议**是具名事件（§3），不是 OpenAI 裸 `data:`。
4. **成功判断**：HTTP 2xx 即成功；失败看 `ApiError`（§4）。
5. **模型 key 必须与接口类型匹配**：对话接口只接受 `modelType=CHAT` 的模型，生图接口只接受 `modelType=IMAGE`。用错会返回 `400`（报文里会写明该 key 实际是什么类型），并且**不会产生任何请求成本**。因此模型选择器要按 `?type=` 分别取列表——主列表与模型家族**都**支持该参数，用哪个都行：

```
GET /api/v1/models?type=CHAT            → 对话选择器
GET /api/v1/models/families?type=IMAGE  → 绘画面板

POST /api/v1/chat/completions     {"model":"deepseek-v4.1-flash"}     ← CHAT
POST /api/v1/images/generations   {"model":"gpt-image-2", ...}        ← IMAGE

用错时：
400 {"message":"Model 'gpt-image-2' is registered as type IMAGE and cannot be used for chat"}
```

> 这条以前没有校验，绘画模型能被当对话模型调用，那一轮还会以图像模型的行写进会话消息表——同一个会话按模型统计、查单价时会读到另一套数据。现在两条链路共用一份类型判断。

### 附件资源类型

文件与生图相关响应现在都包含服务端分配的 `assetType`：

| 值 | 前端用途 |
|---|---|
| `reference` | 用户上传的参考图 |
| `generated` | 文生图生成结果 |
| `edit_result` | 智能修图结果 |
| `chat_attachment` | 普通聊天附件 |

该字段不由客户端提交，前端可以直接按它筛选或分流，不需要根据文件名、MIME 或接口来源自行猜测。

## 3. 流式协议

`POST /api/v1/chat/stream` → `text/event-stream`，帧为 `event:<name>` + `data:<json>`。

```
event:message.created
data:{"requestId":"...","conversationId":2,"messageId":null,"model":"deepseek-v4.1-flash"}

event:message.thinking     （推理链，可选）
data:{"content":"..."}

event:message.delta
data:{"content":"一"}

event:message.completed    （正常结束）
data:{"content":"一、二、三！"}

event:message.cancelled    （被 POST /api/v1/chat/requests/{requestId}/cancel 停止）
data:{"content":"已生成的部分内容"}

event:message.failed       （上游失败）
data:{"message":"..."}
```

- **`message.created` 里的 `requestId` 就是停止生成所需的参数**；
- 三种终止帧（`completed` / `cancelled` / `failed`）互斥，收到其一即结束；
- 另有一个阻塞入口 `POST /api/v1/chat/completions`，一次性返回完整结果。

## 4. 错误结构

失败时返回统一 `ApiError`：

```json
{
  "timestamp": "2026-10-15T02:14:07.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed",
  "path": "/api/v1/chat/completions",
  "details": { "messages": "messages must not be empty" }
}
```

| 状态码 | 含义 |
|---|---|
| `400` | 参数校验失败（`details` 里是字段级原因）；或模型类型与接口不匹配（§2.5） |
| `401` | 未登录或令牌失效 |
| `403` | 账号被停用 |
| `404` | 资源不存在或不属于当前账号 |
| `409` | 冲突（如登录名已占用） |
| `413` | 上传超限 |
| `402` | New API 用户余额不足；本次模型调用不会发往上游 |
| `429` | 触发限流（带 `Retry-After`） |
| `502` | 余额检查通过后，上游模型网关调用失败 |
| `503` | New API 计费查询不可用、返回数据非法，或用户 Relay Token 正在创建 |
| `500` | 存储等内部故障（对外固定文案） |

> **没有 `code` 字段**：判断成功用 HTTP 状态码即可。`5xx` 的 `message` 是固定的对外文案，细节只进服务端日志。

## 5. 鉴权

```
Authorization: Bearer <accessToken>
```

- 登录：`POST /api/v1/auth/login`（也可 `register`），返回 `accessToken` + `refreshToken`；
- `accessToken` 约 30 分钟过期，用 `POST /api/v1/auth/refresh` 换新（**轮换**，旧的立即失效）；
- 登出：`POST /api/v1/auth/logout`。

模型目录、会话列表、附件、智能体等账号相关接口都使用同一套严格 Bearer 解析。请求接口不接受
`X-User-Id`，也不会回退到演示账号；缺少、过期或非法令牌统一返回 `401`。详见
`docs/auth-api.md` §8。

## 6. Token 计费与余额

所有用户 AI 调用都使用当前账号绑定的 New API Relay Token，包括 Chat、Agent、图片生成、
智能修图和 AI Canvas。前端不需要提交或保存 New API Key，也不存在平台本地积分账户。

`GET /api/v1/usage/token` 需要 `Authorization: Bearer <accessToken>`，返回当前绑定 Token 的
`totalGranted`、`totalUsed`、`totalAvailable`、`unlimitedQuota` 等字段，以及后端换算用的
`quotaPerUnit`、`usdExchangeRate` 和已折算好的 `*Cny` 金额。余额为零时该查询仍返回
`200`，由前端展示余额并引导用户前往 New API 充值。

前台余额**默认按美元展示**，金额由 `totalAvailable / quotaPerUnit` 现算，与 New API 后台
显示的 `$` 数字一致；用户可切换到人民币，人民币优先使用接口返回的 `*Cny` 字段。
换算口径与响应字段说明见 `docs/new-api-billing.md` §6。

AI 调用前，服务端会实时读取 New API `data.user_quota.total_available`：

- `<= 0` 返回 `402`，不会调用模型；
- usage 缺失、非法、负数、溢出或 New API 不可用返回 `503`；
- 本服务不保存积分、余额或扣费流水，实际 Token 扣费以 New API 为准。

完整绑定流程、配置项和上线检查见 `docs/new-api-billing.md`。

## 7. 已知的上游限制

`POST /api/v1/images/generations` 的**契约已实现且有测试**，但当前 New API 图像分组曾因上游账号容量不足返回
`No available compatible accounts`，真实生成可能暂时不可用（详见 `docs/image-api.md` §6）。该限制与前端配置无关；
服务端始终使用当前用户的 New API Relay Token，不使用 `LLM_IMAGE_API_KEY`、供应商 Key 或
可选的 `APP_LLM_API_KEY`。
