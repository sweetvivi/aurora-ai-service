# 会话与消息接口（`conversation`）

> 状态：已实现。按账号查询历史会话、查看消息记录、重命名与删除。
> 涉及代码：`modules/conversation`（Controller / Service / 实体仓储 / DTO）、`modules/auth`（账号解析）。
> 接口对应 `docs/ai1foo-rebuild-plan.md` §10 契约表的「会话列表/CRUD」与「消息分页」。

---

## 1. 账号归属

所有接口都必须携带 `Authorization: Bearer <accessToken>`，并以令牌对应的账号为范围。缺少、
过期或非法令牌统一返回 `401`；这些接口不会接受 `X-User-Id`，也不会回退到演示账号。

> `app.security.trust-user-header` 只保留给历史非请求解析路径；请求接口不得把它当作授权机制。

**越权处理**：查询、重命名、删除都按 `(会话 ID, 账号 ID)` 联合匹配。别人的会话返回 `404` 而不是 `403`，避免通过状态码探测其他账号的数据是否存在。

## 2. 接口总览

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/conversations` | 历史会话列表，按最后更新时间倒序，分页 |
| `POST` | `/api/v1/conversations` | 显式创建空会话（可选标题） |
| `GET` | `/api/v1/conversations/{id}` | 会话详情 |
| `GET` | `/api/v1/conversations/{id}/messages` | 消息记录，游标分页 |
| `POST` | `/api/v1/conversations/{id}/messages` | 追加一条用户消息，**不调用模型** |
| `PATCH` | `/api/v1/conversations/{id}` | 重命名 |
| `DELETE` | `/api/v1/conversations/{id}` | 软删除 |
| `POST` | `/api/v1/conversations/batch-delete` | 批量软删除，单次最多 100 个 |

## 3. 历史会话列表

`GET /api/v1/conversations?page=0&size=20`

| 参数 | 默认 | 约束 | 说明 |
|---|---|---|---|
| `page` | `0` | ≥ 0 | 页码，从 0 开始 |
| `size` | `20` | 1–100 | 每页条数，超出上限会被截断为 100 |

```json
{
  "items": [
    {
      "id": 1,
      "title": "用一句话介绍你自己",
      "conversationType": "CHAT",
      "modelId": 1,
      "messageCount": 4,
      "createdAt": "2026-10-14T17:31:36",
      "updatedAt": "2026-10-14T17:35:02"
    }
  ],
  "total": 1,
  "page": 0,
  "size": 20
}
```

只返回 `status = ACTIVE` 的会话；标题由首条 user 消息自动生成（可用 `PATCH` 覆盖）。

## 4. 消息记录（游标分页）

`GET /api/v1/conversations/{id}/messages?cursor=&size=20`

| 参数 | 默认 | 说明 |
|---|---|---|
| `cursor` | 无 | 上一次响应的 `nextCursor`，即「已取到的最后一条消息 ID」；不传表示从头开始 |
| `size` | `20` | 1–100，超出截断 |

```json
{
  "items": [
    {
      "id": 1,
      "sequenceNo": 1,
      "role": "user",
      "content": "用一句话介绍你自己",
      "status": "COMPLETED",
      "promptTokens": null,
      "completionTokens": null,
      "totalTokens": null,
      "errorCode": null,
      "createdAt": "2026-10-14T17:31:33"
    }
  ],
  "nextCursor": 2,
  "hasMore": true
}
```

翻页方式：把 `nextCursor` 原样回传；`hasMore=false` 且 `nextCursor=null` 表示到底。

为什么用游标而不是 `page`：会话消息会持续追加，偏移分页在追加时会出现重复或漏读；按主键游标则稳定，且 `WHERE id > ?` 走主键索引，翻得再深也不变慢。

失败轮次也会返回（`status=FAILED`、`content` 为空串、带 `errorCode`），便于前端展示重试入口。

## 5. 重命名

`PATCH /api/v1/conversations/{id}`

```json
{ "title": "产品对比" }
```

`title` 必填、去首尾空格、最长 255 字符。返回更新后的会话摘要。

## 6. 删除

`DELETE /api/v1/conversations/{id}` → `204 No Content`

**软删除**：会话置为 `status=DELETED` 并写入 `deleted_at`，从列表和详情中消失，但消息记录保留，便于审计与后续恢复。重复删除返回 `404`。

## 7. 错误语义

| 状态码 | 触发条件 |
|---|---|
| `400` | 分页参数非法、标题为空或超长、批量删除的 `ids` 为空或超过 100 个 |
| `401` | 缺少、过期或非法的 access token |
| `404` | 会话不存在、已删除，或不属于当前账号 |

## 8. 与对话接口的衔接

`POST /api/v1/chat/completions` 与 `/api/v1/chat/stream` 现在也按当前账号归属：

- 新建会话归属 `CurrentUser.userId()`；
- 传入 `conversationId` 时按 `(ID, 账号)` 校验，别人的会话直接 `404`；
- 因此「先对话、再拉历史列表、再拉消息」是同一条账号链路，不需要额外参数。

## 9. 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `app.chat.title-max-length` | `60` | 自动标题截断长度 |

## 10. 测试

| 用例类 | 覆盖 |
|---|---|
| `ConversationControllerTest` | 无令牌 401、账号隔离、跨账号 404、游标翻页、单页边界、重命名、空标题 400、软删除保留消息、size 截断、OpenAPI 路径 |
| `ChatServiceTest` | 会话归属取自已解析账号（不信任请求体）、失败落库、持久化开关 |
| `CurrentUserResolverTest` | 请求接口仅接受 Bearer、无令牌 401、非法 Bearer 不降级、历史开发头兼容路径的账号与站点校验 |

```powershell
.\mvnw.cmd test
```

## 11. 尚未覆盖（后续阶段）

- 会话分组与群组对话（`project-structure.md` 中 `conversation` 模块的 `groups/*` 系列）；
- 消息删除与清空、单条评价（`comment/lists`）；
- 会话分享快照。
