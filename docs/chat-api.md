# AI 对话接口（`/api/v1/chat`）

> 状态：已实现并跑通真实上游。上游为 OpenAI 兼容网关，配置项在 `app.llm.*`。
> 涉及代码：`modules/chat`（接口与编排）、`infra/llm`（上游适配）、`modules/conversation`（会话落库）。

---

## 1. 接口总览

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/chat/completions` | 阻塞式对话，一次性返回完整回答 |
| `POST` | `/api/v1/chat/stream` | 同一轮对话的 SSE 流式版本 |
| `POST` | `/api/v1/chat/requests/{requestId}/cancel` | 停止正在进行的流式生成 |

前两个入口共用同一个请求体（`ChatCompletionRequest`）。

## 2. 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `messages` | array | 是 | 1–100 条 | 上下文，按时间正序；至少含一条 `role=user` |
| `messages[].role` | string | 是 | `system` / `user` / `assistant` / `tool` | 消息角色 |
| `messages[].content` | string | 是 | ≤ 32000 字符 | 消息正文 |
| `model` | string | 否 | ≤ 128 字符 | 模型目录 key，缺省取 `app.llm.default-model` |
| `temperature` | number | 否 | 0.0–2.0 | 采样温度 |
| `maxTokens` | integer | 否 | 1–32768 | 生成上限 |
| `conversationId` | integer | 否 | 必须存在 | 追加到已有会话；缺省则新建会话 |
| `title` | string | 否 | ≤ 255 字符 | 仅在新建会话时使用 |

```jsonc
{
  "model": "deepseek-v4.1-flash",
  "messages": [
    { "role": "system", "content": "You are concise." },
    { "role": "user", "content": "Say pong" }
  ],
  "temperature": 0.7,
  "maxTokens": 512,
  "conversationId": 12,
  "title": "First chat"
}
```

> 落库策略：只持久化**最后一条 user 消息**与本次 assistant 回复，`messages` 中的历史由调用方自行维护。这样重复提交同一轮不会产生重复历史。

## 3. 非流式响应

`POST /api/v1/chat/completions` → `200 application/json`

```json
{
  "conversationId": 1,
  "messageId": 2,
  "model": "deepseek-v4.1-flash",
  "content": "我是 DeepSeek，一个由深度求索公司创造的 AI 助手……",
  "finishReason": "stop",
  "usage": { "promptTokens": 15, "completionTokens": 32, "totalTokens": 47 },
  "latencyMs": 3022
}
```

`conversationId` / `messageId` 在 `app.chat.persistence-enabled=false` 时为 `null`。

每次对话在转发上游前都会读取当前用户 New API Relay Token 的实时额度：

- `data.user_quota.total_available <= 0` → `402`，不会调用模型；
- 余额查询缺失、非法或 New API 不可用 → `503`；
- 本服务不扣本地积分，实际 token 费用由 New API 结算。

完整规则见 `docs/new-api-billing.md`。

## 4. 流式响应

`POST /api/v1/chat/stream` → `200 text/event-stream`，帧格式为 `event:<name>` + `data:<json>`。

| 事件 | 载荷 | 说明 |
|---|---|---|
| `message.created` | `{requestId, conversationId, messageId, model}` | 会话已创建并落库，流开始；`requestId` 用于停止生成 |
| `message.thinking` | `{content}` | 推理链增量（上游返回 `reasoning_content` 时才有） |
| `message.delta` | `{content}` | 可见回答增量 |
| `message.completed` | `{content}` | 完整回答，流正常结束 |
| `message.cancelled` | `{content}` | 生成被停止，载荷为已生成的部分回答；该帧后流结束 |
| `message.failed` | `{message}` | 上游失败，该帧后流结束 |

```
event:message.created
data:{"requestId":"4b4cebd5-13f5-4464-8ae1-2bdd286297bb","conversationId":2,"messageId":null,"model":"deepseek-v4.1-flash"}

event:message.delta
data:{"content":"一"}

event:message.completed
data:{"content":"一、二、三！"}
```

流式响应会合并上游各帧返回的 `usage`，并在正常完成、主动停止或客户端断连时写入 assistant 消息。若上游完全不返回 usage，本地计数为零；New API 仍是实际扣费的权威来源。

## 5. 停止生成

`POST /api/v1/chat/requests/{requestId}/cancel`

`requestId` 由服务端生成，在 `message.created` 里下发；客户端拿到后即用它调停止接口。

| 结果 | 状态码 | 说明 |
|---|---|---|
| 已发出停止信号 | `204` | 生成正在进行，已请求停止 |
| 生成已经结束 | `204` | 结束后的短时间内 `requestId` 仍被保留，重复停止是幂等的 |
| 该账号没有此请求 | `404` | 从未见过该 id，或它已超出保留窗口 |

### 停止时发生什么

1. 流式循环在**每个 chunk 之前**检查停止标记，命中即抛异常；
2. 该异常从上游适配器的 try-with-resources 中展开，**真正关闭上游连接**——是切断生成，而不是只忽略后续 token；
3. 已生成的部分内容落库为 `chat_messages.status = CANCELLED`（`error_code` 为空，这不是错误）；
4. 向客户端补发 `message.cancelled`，载荷是已生成的部分回答；
5. **不会**再发 `message.completed`。

实测序列：`message.created` → 2353 个 `message.thinking` → 83 个 `message.delta` → `message.cancelled`。

### 两个边界情况

- **落库内容 = 客户端已收到的内容**。token 是「先推送、成功后才累加」，推送失败的那个 token 不会进入落库内容——否则库里的会话会和用户屏幕上看到的不一致。
- **客户端直接断开**（关页面、断网）产生同样的结果：推送失败导致循环展开、上游连接关闭，已收到的部分同样记为 `CANCELLED`。区别只在日志里。

> ⚠️ `requestId` 只存在于进程内存，不落库。多实例部署时，停止请求必须路由到承载该生成的实例；规划中的 Redis 阶段会把它变成共享状态。

## 6. 错误语义

| 状态码 | 触发条件 | 响应体 |
|---|---|---|
| `400` | Bean Validation 失败 | `{status,message,path,details:{字段:原因}}` |
| `400` | 没有任何 `role=user` 的消息 | `{...,"message":"messages must contain at least one user message"}` |
| `404` | `conversationId` 不存在 | `{...,"message":"Conversation 999999 was not found"}` |
| `404` | 模型已下架（`llm_models.status != ACTIVE`） | `{...,"message":"Model 'x' is not available"}` |
| `400` | 模型类型不是 `CHAT`（绘画/视频/音频模型） | `{...,"message":"Model 'gpt-image-2' is registered as type IMAGE and cannot be used for chat"}` |
| `402` | New API 用户余额小于等于 `0` | `{...,"message":"Insufficient account balance. Top up the New API account and retry"}` |
| `503` | New API 计费查询不可用、usage 非法，或用户 Relay Token 正在创建 | `{...,"message":"New API usage service is unavailable"}` |
| `502` | 上游不可达 / 返回非 2xx / 报文不可解析 | `{...,"message":"Model gateway returned HTTP 401: ..."}` |
| `404` | 停止一个不属于本账号或已过保留窗口的生成 | `{...,"message":"Request 'x' is not in flight"}` |

`502` 时该轮仍会落库为 `chat_messages.status = FAILED`、`error_code = LLM_INVOCATION_FAILED`，不会留下孤立的 user 轮次。上游错误体最多保留 500 字符，且**不含** API Key。

## 7. 数据落库

| 表 | 写入内容 |
|---|---|
| `conversations` | 首次调用创建；`title` 取首条 user 消息（截断至 `app.chat.title-max-length`），`message_count` 随消息累加 |
| `chat_messages` | 每轮 2 条（user + assistant），`sequence_no` 在会话内单调递增，assistant 行记录 token 用量与状态 |

assistant 行的 `status` 有三种：`COMPLETED`（正常结束）、`FAILED`（上游失败）、`CANCELLED`（被停止或客户端断开，`content` 为已生成的部分内容）。

会话归属：由 `CurrentUserResolver` 解析出的当前账号决定（Bearer 令牌优先，见 `docs/auth-api.md` §8）；`conversationId` 按 `(会话 ID, 账号 ID)` 联合校验，别人的会话返回 `404`。

## 8. 模型解析

1. 请求未带 `model` → 取 `app.llm.default-model`。
2. 用 key 查 `llm_models`，**可见性与模型目录完全一致**：`site_id IS NULL`（全局）或 `site_id = 当前账号所属站点`，按 `id` 升序取第一条。
   命中且 `status=ACTIVE` 时，**真正发给上游的是 `llm_models.route`**（缺省回退为 key），因此目录可以对外暴露友好 key 而内部路由到真实模型名。
3. **类型必须是 `CHAT`**：key 指向 `IMAGE` / `VIDEO` / `AUDIO` 的模型时返回 `400`，请求不会转发到上游。
4. 命中可见条目但 `status != ACTIVE` → `404`（下架是明确决定，必须拦住）。
5. **完全不可见（含他人站点的行）→ 默认 `404`**，不把 key 转发上游。唯一的例外是「目录里还没有该类型的任何一行」（首次部署、同步未跑），此时放行，见 `docs/model-catalog-api.md` §6.1。

**为什么第 3 条必须拦住。** `model_type` 一度只用于目录筛选，**执行路径完全不读它**，于是绘画模型能被当对话模型调用：请求被转发到上游的 chat completions，而这一轮还会以图像模型的行写进 `chat_messages.model_id`——对话历史里因此记录了一个根本没参与对话的模型，按 `model_id` 查定价、做用量归属和站点报表时都会读到另一套数据。反向同理，语言模型可以被送到 `/images/generations`。

现在对话与生图两条链路都走 `modules/model/ModelUseResolver`，**类型与状态只有一份判断**（本项目此前已因"同一张表两条路径规则不一致"出过一次站点越权，见 `docs/model-catalog-api.md`）。返回 `400` 而不是 `404`：该 key 确实存在、在目录里也看得到，只是由另一个接口承载；`404` 会把调用方引向"去找一个并不缺的模型"。

第 5 条比第 3 条更严：类型校验只在查得到目录行时生效，而**未登记的 key 默认连"查得到"这一步都过不去**。

> **这条曾经是"放行"，改掉的代价与理由。** 2026-10-29 之前，未登记的 key 会原样转发上游，理由是「让新模型先跑通再补目录」。代价是客户端递一个上游路由名（`yunwu/gpt-image-2` 这类）就能绕过目录、类型校验和站点可见性，而且失败了看不出是谁的问题。`app.llm.allow-unlisted-models` 默认 `false` 之后改为拒绝；要短期放开（上游刚上线、目录还没同步）设它为 `true`，**不要为此改代码**。
>
> 改这条时容易踩的坑：空目录下**每个** key 都算未登记，一刀切拒绝会把对话、图片、视频一起打成 `404`。所以判据是「目录里是否曾经有过该类型的任何一行」，不是「这个 key 在不在」，细节见 `model-catalog-api.md` §6.1。

## 9. 配置项

| 配置 | 默认值 | 说明 |
|---|---|---|
| `app.llm.base-url` | `https://api.example.com/v1` | New API 的 OpenAI 兼容入口，可用 `LLM_BASE_URL` 覆盖 |
| `app.llm.api-key` | 空 | 仅宿主/兼容调用使用；用户对话使用自己的 Relay Token，不读该值 |
| `app.llm.default-model` | `deepseek-v4.1-flash` | 缺省模型 |
| `app.llm.connect-timeout` / `read-timeout` | `10s` / `120s` | 连接与整体超时 |
| `app.new-api.*` | 见 `application.yml` | 用户、PAT、Relay Token、余额查询和加密配置，详见 `docs/new-api-billing.md` |
| `app.chat.persistence-enabled` | `true` | 关闭后不写会话表 |
| `app.chat.stream-timeout` | `5m` | SSE 连接最长存活时间 |
| `app.chat.request-retention` | `2m` | 生成结束后 `requestId` 仍可停止的时长，用于让停止按钮幂等 |
| `app.chat.max-tracked-requests` | `10000` | 在途请求登记上限，超出后淘汰最旧的已结束记录 |

## 10. 测试

| 用例类 | 覆盖 |
|---|---|
| `LlmSseParserTest` | SSE 帧解码：内容/推理增量、`[DONE]`、空行注释、坏报文 |
| `ProtocolAwareLlmClientTest` | 本地 `HttpServer` 桩：鉴权头、报文、可选参数、401/503 映射、流式增量；供应商凭据覆盖全局配置 |
| `ProtocolAwareLlmClientProtocolTest` | 逐协议报文形状与响应解析（OpenAI / Anthropic / Gemini / Azure） |
| `ChatServiceTest` | 编排逻辑（Mockito）：默认模型、目录路由、**站点可见性（不认领他人站点的行）**、**拒绝绘画模型且不产生会话记录**、失败落库、持久化开关、**停止后保留已收到内容**、客户端断开、`message.created` 携带 `requestId` |
| `ChatRequestRegistryTest` | 在途登记：取消送达、重复取消幂等、保留窗口、账号隔离、内存有界 |
| `ChatControllerTest` | MockMvc 全链路：落库、多轮追加、400/404/502、SSE 帧序列、停止接口 `204/404` 与账号隔离、OpenAPI 路径 |

```powershell
.\mvnw.cmd test
```
