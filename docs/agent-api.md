# 用户端智能体接口

> 状态：已实现。用户端创建、维护和查询自己拥有的智能体；管理端仍使用
> `/api/admin/v1/agents`，两套写入权限相互独立。
>
> 涉及代码：`modules/agent`（Controller / Service / DTO / Entity / Repository）。

## 1. 账号归属

智能体接口要求携带 `Authorization: Bearer <accessToken>`，并使用令牌对应的账号身份。
缺少、过期或非法令牌统一返回 `401`；请求接口不接受 `X-User-Id`，也不会回退到演示账号。

`app.security.trust-user-header` 只保留给历史非请求解析路径，不能作为 HTTP 接口的授权机制。

用户端写入接口只允许操作当前账号拥有的智能体。查询不到、已删除或属于其他账号时统一返回
`404`，避免通过状态码探测其他用户的数据。

## 2. 接口总览

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/agents` | 创建属于当前账号的智能体，返回 `201` |
| `PATCH` | `/api/v1/agents/{id}` | 字段更新当前账号拥有的智能体 |
| `DELETE` | `/api/v1/agents/{id}` | 软删除当前账号拥有的智能体，返回 `204` |
| `GET` | `/api/v1/agents/mine` | 分页返回当前账号创建的智能体 |
| `GET` | `/api/v1/agents?mine=true` | 等价查询入口，保留给已有前端 |
| `GET` | `/api/v1/agents/{reference}` | 详情；`reference` 支持数字 ID 或 `sn` |
| `GET` | `/api/v1/agents/conversations` | 当前账号的智能体会话列表，可按智能体或分类分页筛选 |

## 3. 创建智能体

`POST /api/v1/agents`

`name` 与 `systemPrompt` 必填，其余字段均可选：

```json
{
  "name": "Python 助手",
  "description": "帮助编写、调试和优化 Python 代码",
  "iconUrl": "https://example.com/avatar.png",
  "systemPrompt": "你是一名严谨的 Python 工程师。",
  "chatPrologue": "今天要处理哪段 Python 代码？",
  "categoryId": 1,
  "uiType": 1,
  "classify": "编程语言专家",
  "childClassify": "Python",
  "chatSuggest": true,
  "tags": ["python", "coding"],
  "modelIds": [1],
  "defaultModelId": 1,
  "abilityKeys": ["web-search"],
  "questions": ["如何优化这段代码？"]
}
```

`modelIds` **最多一个元素**（`defaultModelId` 与之相同），传多个返回
`400 每个智能体只能绑定一个对话模型`。留空表示跟随站点默认模型
（`app_site_settings.default_model`）；智能体的提示词与工具集是围绕某个模型调过的，
换模型等于换脑子，而站内也没有计费闸门兜住成本，所以前台不提供模型切换入口。

创建时由服务端固定以下属性，客户端不能通过用户端接口覆盖：
| 字段 | 创建值 | 说明 |
|---|---|---|
| `ownerUserId` | 当前账号 ID | 归属身份只取解析后的账号 |
| `agentType` | `CUSTOM` | 用户端不能创建 `OFFICIAL` |
| `scope` | `0` | 默认私有 |
| `visibility` | `PRIVATE` | 默认不可被其他用户检索 |
| `publishStatus` | `DRAFT` | 默认草稿，发布由管理端处理 |
| `status` | `ACTIVE` | 创建后立即对拥有者可用 |
| `score` | `5.00` | 初始评分 |

请求体中的发布、可见性、评分、状态、归属等管理字段会被忽略；需要发布或改分时使用后台接口
`/api/admin/v1/agents`。

成功响应为 `201 Created`，正文是完整的 `AgentResponse`，包含 `ownerUserId`、`agentType`、
`scope`、`visibility`、`publishStatus`、`status` 等字段。

## 4. 我创建的智能体

`GET /api/v1/agents/mine?page=0&size=20`

支持以下筛选参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `page` | `0` | 页码，从 0 开始 |
| `size` | `20` | 每页条数，最大 100 |
| `categoryId` | 无 | 分类 ID |
| `keyword` | 无 | 按名称、简介、一级/二级分类模糊搜索 |
| `hot` | 无 | 是否只看热门 |
| `scope` | 无 | `0` 私有 / `1` 公开 |
| `sort` | 默认排序 | `hot` / `score` / `latest` |

返回标准的 `PageResponse`：

```json
{
  "items": [
    {
      "id": 101,
      "ownerUserId": 7,
      "name": "Python 助手",
      "agentType": "CUSTOM",
      "scope": 0,
      "visibility": "PRIVATE",
      "publishStatus": "DRAFT",
      "status": "ACTIVE"
    }
  ],
  "total": 1,
  "page": 0,
  "size": 20
}
```

查询固定附加 `owner_user_id = 当前账号 ID` 与 `status = ACTIVE`。其他账号的私有智能体、已删除
智能体和平台官方智能体不会出现在该结果中。

## 5. 更新与删除

`PATCH /api/v1/agents/{id}` 支持创建请求中的所有内容字段，语义为：`null` 保持原值，集合传
`[]` 清空。发布状态、可见性、范围、评分、归属和类型同样不会被用户端请求修改。

`DELETE /api/v1/agents/{id}` 执行软删除：设置 `status=DELETED`、`visibility=PRIVATE`、
`publishStatus=DELETED`。历史智能体会话和消息保留。

## 6. 错误语义

| 状态码 | 触发条件 |
|---|---|
| `400` | 缺少名称/提示词、字段超长、模型类型或分类归属不合法 |
| `404` | 智能体不存在、已删除或不属于当前账号 |

## 7. 智能体会话历史

`GET /api/v1/agents/conversations?page=0&size=20`

接口始终只返回当前账号的会话；可选参数如下：

| 参数 | 说明 |
|---|---|
| `agentId` | 限定到单个智能体；设置后优先于 `categoryId` |
| `categoryId` | 限定到指定智能体分类，可用于教育等工作台独立展示历史 |
| `keyword` | 与 `categoryId` 一起使用时，按会话标题或智能体名称模糊搜索 |
| `page` / `size` | 从 0 开始分页，每页最多 100 条 |

会话本身仍保存在统一的智能体会话表中。分类筛选只改变查询范围，不会把 AI 教育会话复制、迁移或混入超级智能体的会话列表。会话详情、消息读取与删除仍使用现有 `/api/v1/agents/conversations/{id}` 接口，并继续校验当前账号归属。

## 8. 测试

`AgentControllerTest` 覆盖：

- 创建后 `ownerUserId` 取当前账号；
- 强制 `CUSTOM + PRIVATE + DRAFT + scope=0`；
- 用户尝试通过创建或更新请求发布时字段仍保持服务端默认值；
- `GET /agents/mine` 与 `GET /agents?mine=true` 只返回当前账号创建的记录；
- 官方公开智能体和其他账号的智能体不会被“我创建的”查询混入；
- OpenAPI 同时发布创建、更新和 `mine` 路径。
