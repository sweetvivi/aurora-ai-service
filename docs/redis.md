# Redis：跨实例共享状态

> 开关：`app.redis.enabled`（`REDIS_ENABLED`），默认 **false**
> 代码：`common/ratelimit`（限流端口 + 内存实现）、`common/coordination`（生成请求账本/广播端口）、
> `infra/redis`（Redis 实现）、`RedisListenerConfig`（pub/sub 容器）

## 什么时候打开

默认关闭，单实例部署跑得好好的。只有下面两种情况需要它：

1. **多副本**。进程内计数与进程内取消在 N 个副本下都是错的：限流额度变成 `limit × N`；
   「停止生成」打到别的副本上只能 404。
2. **重启不想丢计数**。注册/登录限流现在重启归零，打开后窗口跨重启保留。

打开需要一台 Redis 6+（本仓库的脚本只用 5.0 的语法，兼容 5.x）。

```yaml
# docker-compose.yml
redis:
  image: redis:7-alpine
  restart: unless-stopped
  command: ["redis-server", "--appendonly", "no", "--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"]
  volumes:
    - redis-data:/data
```

限流与「进行中的请求」都是**可重建的短生命周期数据**，`appendonly no` + `allkeys-lru` 就够：
丢了最多是额度重新开始、正在跑的生成少一次跨实例取消，不会丢业务数据。

```bash
REDIS_ENABLED=true
REDIS_HOST=redis          # compose 服务名
REDIS_PORT=6379
REDIS_PASSWORD=
```

## 迁了什么

| 状态 | 之前 | 现在（`enabled=true`） | 键 |
| --- | --- | --- | --- |
| 限流计数 | 进程内 `ConcurrentHashMap`，按实例计数 | Redis 计数，N 个副本共用一个窗口 | `rl:{业务键}` |
| 进行中的生成 | 进程内 map，取消只能打到本实例 | Redis 状态 + pub/sub 广播，取消任意实例都生效 | `chat:req:{账号}:{请求 id}` |

流式循环**没有**改成每块读一次 Redis：一条回答要推几百个 chunk，那点延迟和连接量不值当。
真正的取消标志仍在持有连接的进程里，Redis 只回答「这条请求存在吗、什么状态」，并用一条广播
让持有者自己翻标志。

## 失败时的行为（重要）

先分清两种故障：**启动时**连不上说明部署配错了（`REDIS_ENABLED=true` 却连不到），监听容器
`start()` 直接抛异常，应用起不来 —— 有意为之，配置错误应该在部署阶段暴露，而不是悄悄降级。
下面说的是**运行期**故障（Redis 重启、网络抖动）：

| 场景 | 行为 | 为什么 |
| --- | --- | --- |
| 限流时 Redis 不可用 | **放行**（fail-open），打 warn | 限流是防护措施，不是业务正确性。Redis 挂了不该让登录、注册、发图同时不可用 |
| 取消时 Redis 不可用 | 退化为单实例行为：打到持有者能停，打到别的副本 404 | 停止不是事务操作，用户重试即可 |
| 广播消息丢失 | 这次停止不生效，生成自然跑完 | pub/sub 是 at-most-once；为停止引入确认与重放，复杂度远超收益 |

## 实现细节

- **窗口语义与内存版一致**：按每个键的第一次尝试起算，不是整点对齐（理由见 `RateLimiter`）。
  一次请求可以记多单位（生图按张计费），被拒的批次整体回滚。
- **时间取 Redis 的**：脚本里用 `TTL` 推窗口剩余，不拿应用服务器的时钟 —— 多机差几十秒就会
  让两个副本对「窗口结束没有」得出不同结论。
- **`clear()` 用 SCAN 不用 `KEYS`**：`KEYS` 在大库是 O(n) 阻塞命令。前缀由
  `app.rate-limit.key-prefix` 控制，避免误删同一实例上别的服务的键。
- **取消状态只从 `ACTIVE` 翻**：`SET ... XX` 会顺手清掉 TTL，所以脚本显式读一次 TTL 再写回
  （Redis 5 没有 `KEEPTTL`）。

## 验证

```bash
# 需要本机/容器里有 Redis；没有该测试类会整体跳过，不会让 CI 变红
./mvnw test -Dtest='RedisRateLimiterTest,RedisChatCoordinationTest'
```

`RedisChatCoordinationTest` 用两个 `ChatRequestRegistry` 实例模拟两个进程，共享同一份 Redis
状态与同一条广播总线：B 收到取消 → 状态翻成 CANCELLED → 广播 → A 本地的流式循环看到标志。

手工确认：

```bash
redis-cli --scan --pattern 'rl:*'        # 限流计数（带 TTL）
redis-cli --scan --pattern 'chat:req:*'  # 进行中/刚结束的生成
redis-cli ttl rl:register:203.0.113.7
```
