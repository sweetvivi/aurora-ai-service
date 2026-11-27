# 附件与图片接口（`file`）

> 状态：已实现。上传附件与图片、查看元数据、下载、删除、按会话过滤。
> 涉及代码：`modules/file`（Controller / Service / 实体仓储 / DTO）、`infra/storage`（存储端口 + 本地实现）。
> 接口对应 `docs/ai1foo-rebuild-plan.md` §10 契约表的「文件」与 §8.4 文件流规划。

---

## 1. 接口总览

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/files` | 上传，`multipart/form-data` |
| `GET` | `/api/v1/files` | 附件列表，可按会话过滤 |
| `GET` | `/api/v1/files/{id}` | 元数据 |
| `GET` | `/api/v1/files/{id}/content` | 下载文件字节 |
| `DELETE` | `/api/v1/files/{id}` | 删除 |

所有接口都以当前账号为范围（账号来源见 `docs/conversation-api.md` §1）。别人的附件返回 `404` 而非 `403`。

**图片没有单独接口**：`image/png`、`image/jpeg`、`image/gif`、`image/webp`、`image/bmp` 都在白名单内，与 PDF、表格走同一条链路。

## 2. 上传

`POST /api/v1/files`，`Content-Type: multipart/form-data`

| 字段 | 必填 | 说明 |
|---|---|---|
| `file` | 是 | 文件内容 |
| `conversationId` | 否 | 挂到某个会话下；会校验会话归属，别人的会话返回 404 |

```bash
curl -X POST http://localhost:8080/api/v1/files \
  -H "Authorization: Bearer <accessToken>" \
  -F "file=@screenshot.png" \
  -F "conversationId=1"
```

响应：

```json
{
  "id": 1,
  "originalName": "screenshot.png",
  "mimeType": "image/png",
  "extension": "png",
  "assetType": "chat_attachment",
  "sizeBytes": 20480,
  "checksumSha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "status": "UPLOADED",
  "conversationId": 1,
  "messageId": null,
  "url": "/api/v1/files/1/content",
  "createdAt": "2026-10-14T18:02:11"
}
```

### 资源类型

`assetType` 表示资源的业务用途，由服务端根据调用入口自动写入，客户端不能通过上传表单指定：

| 值 | 含义 | 产生入口 |
|---|---|---|
| `reference` | 用户上传的参考图 | `POST /api/v1/smart-editing/files` |
| `generated` | 文生图生成结果 | `POST /api/v1/images/generations`；`/retouch` 无参考图的文生图分支 |
| `edit_result` | 智能修图结果 | 抠图、增强、消除、局部重绘、带参考图的 `/retouch` |
| `chat_attachment` | 普通聊天附件 | `POST /api/v1/files` |

该字段会出现在上传、附件列表、附件元数据和生图等所有引用已落库资源的响应中，前端可以按用途区分素材，而不需要推断文件名或 MIME 类型。

### 校验顺序

1. 空文件 → `400`；
2. 大小超过 `app.storage.max-file-size` → `413`；
3. **扩展名**不在白名单 → `400`；
4. **MIME 类型**不在白名单 → `400`；
5. 扩展名与 MIME 不属于同一文件族 → `400`；
6. PNG / JPEG / GIF / WebP / BMP / PDF 的**文件签名**与扩展名不符 → `400`；
7. `conversationId` 非本人会话 → `404`。

扩展名与 MIME **既要各自通过白名单，也必须互相兼容**。例如 `.png` 只能声明
`image/png`，不能用 `image/jpeg` 混过独立校验。客户端可以随意伪造文件名和
`Content-Type`，因此图片与 PDF 还会检查文件头魔数；文本和 Office 文档目前只做白名单与
扩展名/MIME 配对校验。

原始文件名只作为展示用途：服务端会剥掉路径分隔符与控制字符，落盘键完全由服务端生成（`yyyy/MM/dd/<uuid>.<ext>`），客户端无法影响存储位置。

### 写入顺序与补偿

先写 blob，再写元数据；若元数据落库失败，会立即删除刚写入的 blob，避免留下孤儿对象。删除附件时反过来：软删行与投递 `storage.delete` 消息在**同一个事务**里完成，blob 由 `StorageCleanupHandler` 异步删除并按退避重试（排队/重试语义见 [docs/queue.md](queue.md)）。之前那版是 `afterCommit` 里删、失败只记一行警告 —— 进程崩在提交与删盘之间就会永久留一个对象，没人记得它该被删。

## 3. 列表

`GET /api/v1/files?conversationId=&page=0&size=20`

| 参数 | 默认 | 说明 |
|---|---|---|
| `conversationId` | 无 | 只看该会话的附件 |
| `page` | `0` | 页码，从 0 开始 |
| `size` | `20` | 1–100，超出截断 |

只返回 `status=UPLOADED` 的记录，按上传时间倒序。

## 4. 下载

`GET /api/v1/files/{id}/content`

响应带 `Content-Disposition: attachment`（RFC 5987 编码文件名）与 `Content-Length`，`Content-Type` 取上传时的 MIME。

> 一律以 `attachment` 下发而不是 `inline`：上传内容不可信，内联渲染会给存储型 XSS 留出入口。

## 5. 删除

`DELETE /api/v1/files/{id}` → `204 No Content`

软删除：`uploaded_files.status` 置为 `DELETED`，同时清理存储对象。删除后元数据仍在（便于审计），但接口不再返回它；重复删除返回 `404`。

## 6. 错误语义

| 状态码 | 触发条件 |
|---|---|
| `400` | 文件为空、扩展名或 MIME 不在白名单、扩展名与 MIME 不兼容，或图片/PDF 文件签名与扩展名不符 |
| `404` | 附件不存在、已删除、不属于当前账号，或关联会话不属于当前账号 |
| `413` | 超过 `app.storage.max-file-size`，或超过 Servlet 容器上限 |
| `500` | 存储层故障（详情只记日志，响应统一为固定文案） |

## 7. 存储抽象

业务模块只依赖 `infra/storage/FileStorage` 端口：

```java
StoredObject store(InputStream content, String extension);
Resource load(String storageKey);
void delete(String storageKey);
```

当前实现 `LocalFileStorage` 落盘到 `app.storage.local-root`（默认 `./data/uploads`，已在 `.gitignore` 中忽略）。

后续接对象存储时（见 `ai1foo-rebuild-plan.md` §8.4 / `project-audit.md` P2），只需新增一个 S3/MinIO 实现替换该 Bean，`modules/file` 无需改动。届时应补上：预签名直传、quarantine → 扫描 → 解析状态机、生命周期清理。

## 8. 数据表

`uploaded_files`（V1 已存在）保存元数据，**不保存文件字节**。

| 列 | 用途 |
|---|---|
| `storage_key` | 存储键，服务端生成 |
| `original_name` | 展示用文件名，已清洗 |
| `mime_type` / `extension` | 白名单校验结果 |
| `asset_type` | 业务用途：`reference` / `generated` / `edit_result` / `chat_attachment`，由服务端写入 |
| `size_bytes` | 实际字节数（来自存储层，非客户端声明） |
| `checksum_sha256` | 流式计算的校验和 |
| `status` | `UPLOADED` / `DELETED` |

> `extracted_text` 暂未映射：文件转文本属于后续阶段，且该列可能很大，应按审计建议改为存解析状态 + 外部对象键。

## 9. 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `app.storage.local-root` | `./data/uploads` | 本地存储根目录，可用 `STORAGE_LOCAL_ROOT` 覆盖 |
| `app.storage.max-file-size` | `20MB` | 单文件上限 |
| `app.storage.allowed-content-types` | 图片 / PDF / 文本 / JSON / Office | 支持 `image/*` 形式的前缀通配 |
| `app.storage.allowed-extensions` | 与上表对应的扩展名 | 小写、不含点 |
| `spring.servlet.multipart.max-file-size` | `25MB` | 容器上限，略高于业务上限以便返回自定义 413 |

## 10. 测试

| 用例类 | 覆盖 |
|---|---|
| `LocalFileStorageTest` | 落盘与校验和、日期分目录、无扩展名、删除幂等、目录穿越拒绝、缺失对象报错 |
| `FileServiceTest` | 空文件、超限、扩展名/MIME 白名单与文件族配对、图片/PDF 文件签名、落库失败回收 blob、会话归属、跨账号 404、文件名清洗与扩展名解析 |
| `FileControllerTest` | multipart 上传、下载字节与响应头、账号隔离、非法类型 400、空文件 400、会话挂载与越权 404、列表与过滤、删除后清理 blob、OpenAPI 路径 |

```powershell
.\mvnw.cmd test
```

## 11. 尚未覆盖（后续阶段）

- 附件与消息的关联（`message_attachments`）与在对话请求中引用附件；
- 文件转文本 `POST /api/v1/file/yfoo.file2text`；
- 预签名直传、病毒扫描、解析 Worker、保留期与生命周期清理；
- 兼容线上旧路径 `POST /api/v2/file/upload`。
