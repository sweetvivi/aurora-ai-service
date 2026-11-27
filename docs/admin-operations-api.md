# 管理端用户、会话与任务接口

这些接口补齐后端的真实运营数据入口。管理端统一使用 `X-Admin-Key`，
接口前缀为 `/api/admin/v1`，列表分页从 `1` 开始，统一返回：

```json
{ "list": [], "total": 0 }
```

## 用户

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/users` | `keyword`、`status=active\|disabled`、`page`、`pageSize` |
| `POST` | `/users` | `{ "username", "nickname?", "remark?" }` |
| `PATCH` | `/users/{id}/status` | `{ "status": "active"\|"disabled" }` |
| `DELETE` | `/users/{id}` | 无 |

用户名在默认站点内唯一。用户仍有身份、会话或其他外键关联时，删除返回 `409`。
停用只改变平台登录状态，New API Token 和额度仍由 New API 管理。

## 会话

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/conversations` | `keyword`、`page`、`pageSize` |
| `DELETE` | `/conversations/{id}` | 软删除，重复删除返回 `404` |

`modelId` 返回模型目录中的字符串 `modelKey`，不是数据库数字主键。

## 图片任务

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/tasks` | `keyword`、`status`、`page`、`pageSize` |

前端状态映射：

| 本地任务状态 | 接口状态 |
| --- | --- |
| `pending` | `pending` |
| `running` | `generating` |
| `succeeded` | `success` |
| `failed` | `failed` |

只返回 `IMAGE` 类型任务。模型优先返回 `modelKey`，为空时回退到 `upstreamModel`。

## 错误

- 缺少或错误管理密钥：`401`
- 请求参数或状态非法：`400`
- 用户或会话不存在：`404`
- 用户名重复或用户存在关联数据：`409`
- 错误响应沿用后端 `ApiError`。
