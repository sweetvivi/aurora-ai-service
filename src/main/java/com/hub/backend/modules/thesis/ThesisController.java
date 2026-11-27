package com.hub.backend.modules.thesis;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.GlobalExceptionHandler;
import com.hub.backend.common.api.PageResponse;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.common.exception.UnauthorizedException;
import com.hub.backend.modules.appconfig.ChannelResolver;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.thesis.dto.ThesisCatalogResponse;
import com.hub.backend.modules.thesis.dto.ThesisDocumentResponse;
import com.hub.backend.modules.thesis.dto.ThesisDocumentSummaryResponse;
import com.hub.backend.modules.thesis.dto.ThesisFullTextRequest;
import com.hub.backend.modules.thesis.dto.ThesisFullTextResponse;
import com.hub.backend.modules.thesis.dto.ThesisOutlineNode;
import com.hub.backend.modules.thesis.dto.ThesisOutlineRequest;
import com.hub.backend.modules.thesis.dto.ThesisOutlineResultResponse;
import com.hub.backend.modules.thesis.dto.ThesisOutlineUpdateRequest;
import com.hub.backend.modules.thesis.dto.ThesisTopicOptimizeRequest;
import com.hub.backend.modules.thesis.dto.ThesisTopicOptimizeResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Thesis outline generation and editing.
 *
 * <p>Public endpoints (catalogue, topic optimisation, outline preview) are readable without a token
 * because the generator card is the landing page: a visitor can fill it in and see an outline
 * before signing up. Everything that stores work requires a Bearer token.</p>
 */
@RestController
@RequestMapping("/api/v1/thesis")
@Tag(name = "论文写作", description = "大纲生成、大纲编辑、稿件管理与题目优化")
public class ThesisController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ThesisController.class);

    private final ThesisService thesisService;
    private final ThesisCatalogService catalogService;
    private final ThesisTopicOptimizer topicOptimizer;
    private final CurrentUserResolver currentUserResolver;
    private final ChannelResolver channelResolver;
    /** 全文生成的受控有界池，见 {@code AsyncConfig#thesisFullTextExecutor}。 */
    private final ThreadPoolTaskExecutor fullTextExecutor;

    public ThesisController(ThesisService thesisService,
                            ThesisCatalogService catalogService,
                            ThesisTopicOptimizer topicOptimizer,
                            CurrentUserResolver currentUserResolver,
                            ChannelResolver channelResolver,
                            @Qualifier("thesisFullTextExecutor") ThreadPoolTaskExecutor fullTextExecutor) {
        this.thesisService = thesisService;
        this.catalogService = catalogService;
        this.topicOptimizer = topicOptimizer;
        this.currentUserResolver = currentUserResolver;
        this.channelResolver = channelResolver;
        this.fullTextExecutor = fullTextExecutor;
    }

    @Operation(summary = "生成器表单选项",
            description = """
                    返回学历层次 Tab、学科专业二级联动数据与字数档位。

                    独立成接口而不是写死在前端：新增一个学科不需要前端发版，字数档位也与大纲模板
                    保持同一份定义。无需登录。
                    """)
    @ApiResponse(responseCode = "200", description = "表单选项")
    @GetMapping("/catalog")
    public ThesisCatalogResponse catalog(HttpServletRequest httpRequest) {
        AppSiteEntity site = channelResolver.resolve(httpRequest);
        return catalogService.catalog(site.getId(), thesisService.modelAvailable());
    }

    @Operation(summary = "AI 优化题目",
            description = """
                    把口语化题目改写为学术化表述，返回优化结果与一句改动说明（用于按钮下方的提示）。

                    规则匹配顺序：题目 / 专业命中学科词库时套用该学科模板，否则用通用模板。
                    同一题目重复调用会交替给出不同版本，方便用户挑选。无需登录。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "优化结果"),
            @ApiResponse(responseCode = "400", description = "题目过长",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/outlines/optimize")
    public ThesisTopicOptimizeResponse optimizeTopic(@RequestBody ThesisTopicOptimizeRequest request) {
        return topicOptimizer.optimize(request);
    }

    @Operation(summary = "生成大纲（不落库）",
            description = """
                    「立即生成大纲」按钮背后的一步，**不写数据库**，可以无限次调用、随意重试；
                    用户满意后再调用 `POST /api/v1/thesis/documents` 保存为稿件。

                    两套引擎，`useModel` 决定用哪套：

                    - `useModel=false`（缺省）：确定性模板。免费、毫秒级、不消耗 token，可反复点。
                      第三章形态按学历分叉：本科=系统设计与实现，硕士=假设与实证，期刊=识别策略，
                      开题=研究内容与关键问题。
                    - `useModel=true`：调用真实语言模型生成标题层级。需要服务端已配置上游 key；
                      模型返回不可解析、超时或报错时**自动回落到模板**，不会让这个免费功能变成错误页。

                    响应里的 `mode` 告诉客户端实际用了哪套（`MODEL` / `TEMPLATE`），
                    `model` 是实际调用的模型 key。游客可调用模板引擎；模型引擎需要登录
                    （按账号计费的中继令牌无法为游客签发）。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "大纲树 + 实际使用的引擎"),
            @ApiResponse(responseCode = "400", description = "参数校验失败",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/outlines")
    public ThesisOutlineResultResponse generateOutline(@Valid @RequestBody ThesisOutlineRequest request,
                                                       HttpServletRequest httpRequest) {
        // Guests are allowed: the template engine needs no account. The model engine resolves a
        // relay credential per account, so an anonymous caller simply stays on the template.
        CurrentUser currentUser = optionalUser(httpRequest);
        Long siteId = channelResolver.resolve(httpRequest).getId();
        ThesisService.ThesisOutlineResult result = thesisService.preview(request, request.model(), siteId, currentUser);
        return new ThesisOutlineResultResponse(result.outline(), result.mode(), result.model());
    }

    @Operation(summary = "保存大纲为稿件",
            description = "生成并持久化一份稿件，状态为 `OUTLINE`。需要登录。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已保存"),
            @ApiResponse(responseCode = "400", description = "参数校验失败",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未登录或令牌失效")
    })
    @PostMapping("/documents")
    public ThesisDocumentResponse createDocument(@Valid @RequestBody ThesisOutlineRequest request,
                                                 HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        AppSiteEntity site = channelResolver.resolve(httpRequest);
        return thesisService.create(currentUser.userId(), site.getId(), request);
    }

    @Operation(summary = "读取已保存的全文",
            description = """
                    生成全文时如果带了 `documentId`，服务端会把整份结果存回稿件；
                    这里把它原样取回，供「我的稿件 → 查看全文」重新打开阅读器。

                    列表接口的 `hasFullText` 说明有没有可读的正文，`fullTextGeneratedAt`
                    是生成时间 —— 如果它早于 `updatedAt`，说明大纲在生成之后又被改过。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已保存的全文"),
            @ApiResponse(responseCode = "404", description = "稿件不存在 / 不属于当前账号 / 还没有生成全文",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/documents/{id}/full-text")
    public ThesisFullTextResponse fullText(@PathVariable Long id, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return thesisService.fullText(currentUser.userId(), id);
    }

    @Operation(summary = "我的稿件列表",
            description = "按最后更新时间倒序，支持题目关键字过滤；软删除的稿件不返回。需要登录。")
    @GetMapping("/documents")
    public PageResponse<ThesisDocumentSummaryResponse> list(
            @Parameter(description = "题目关键字") @RequestParam(required = false) String keyword,
            @Parameter(description = "页码，从 0 开始") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，最大 100") @RequestParam(required = false) Integer size,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return thesisService.list(currentUser.userId(), keyword, page, size);
    }

    @Operation(summary = "为已生成的全文补写中英文摘要",
            description = """
                    摘要现在随全文一起自动生成，但两种情况下已有全文里没有摘要：
                    功能上线前生成的旧稿件、以及摘要那一次调用失败的稿件。

                    这个接口只重跑「写摘要」这一步：正文从库里读、不再调模型，
                    因此只花一千多 token（正文一次生成是它的十几倍），
                    而且不会改动正文措辞与已保存的文献列表。

                    请求体可留空 `{}`；`model` 不传则用服务端默认模型。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "补好摘要的全文（已存回稿件）"),
            @ApiResponse(responseCode = "400", description = "该稿件还没有全文 / 模型未按格式返回",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未登录"),
            @ApiResponse(responseCode = "402", description = "账户余额不足",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "稿件不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/documents/{id}/abstract")
    public ThesisFullTextResponse regenerateAbstract(
            @PathVariable Long id,
            @RequestBody(required = false) AbstractRequest body,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        // 与全文生成同一道计费闸：补摘要也要花额度，余额为零时先拒绝，
        // 而不是等模型调用到一半才失败。
        thesisService.checkFullTextBilling(currentUser);
        AppSiteEntity site = channelResolver.resolve(httpRequest);
        return thesisService.regenerateAbstract(
                currentUser.userId(), id, body == null ? null : body.model(),
                site.getId(), currentUser);
    }

    /** 补生成摘要的请求体：只允许指定模型，其余一切以库里的稿件为准。 */
    @Schema(name = "ThesisAbstractRequest", description = "补写摘要")
    public record AbstractRequest(
            @Schema(description = "模型 key；不传用服务端默认", example = "deepseek-v4.1-flash")
            String model) {
    }

    @Operation(summary = "稿件详情（含大纲树）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "稿件"),
            @ApiResponse(responseCode = "404", description = "稿件不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/documents/{id}")
    public ThesisDocumentResponse detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return thesisService.get(currentUser.userId(), id);
    }

    @Operation(summary = "保存大纲",
            description = """
                    整树覆盖：改名、增加小节、删除节点都由前端改好树后一次性提交，
                    服务端只做规范化（层级纠正、标题去空白、补齐/去重节点 ID）后整体落库，
                    因此一次保存是原子的，不会出现"删了一半"的中间态。

                    顶层节点即章（level 1），最多三级、最多 400 个节点。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "保存后的稿件，版本号加一"),
            @ApiResponse(responseCode = "400", description = "标题为空、层级过深或节点过多",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "稿件不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/documents/{id}/outline")
    public ThesisDocumentResponse saveOutline(@PathVariable Long id,
                                              @Valid @RequestBody ThesisOutlineUpdateRequest request,
                                              HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return thesisService.saveOutline(currentUser.userId(), id, request);
    }

    @Operation(summary = "删除稿件", description = "软删除：列表立即不可见，数据保留用于审计。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已删除"),
            @ApiResponse(responseCode = "404", description = "稿件不存在或不属于当前账号")
    })
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        thesisService.delete(currentUser.userId(), id);
        return ResponseEntity.noContent().build();
    }

    /* ----------------------------- 全文生成 ----------------------------- */

    @Operation(summary = "生成全文（按章写正文，SSE 推送进度）",
            description = """
                    「生成全文」按钮背后的一步：把大纲逐章展开成正文。

                    行为约定：

                    - **必须已有大纲**。没有大纲就没有约束，生成出来的是一篇形散的文章；大纲是用户
                      已经审阅过的东西，正文必须挂在它下面。
                    - **一章一次模型调用**，顺序执行。每章都重新带上左侧表单的全部要求（题目、学历、
                      文本类型、学科专业、大纲层级、目标字数、三个开关），所以每一章都遵守同一套设定，
                      不会出现「第四章忘了自己是本科系统类论文」这种漂移。
                    - **参考文献不是模型写的**。开启文献引用时，服务端先去公开文献索引检索带 DOI 的
                      中文文献，把它们作为**唯一可引用清单**注入提示词；正文里的 `[n]` 与最终参考文献
                      列表一一对应。`参考文献` 与 `致谢` 两节由服务端从该清单组装，不交给模型。
                    - **按 token 计费，与大纲 / 对话共用同一份 New API 余额**。开流前先校验登录与余额：
                      未登录 `401`、余额为零 `402`、中继未就绪 `503`，不会先写两章再停下。
                      客户端中止只结算已经产生的 token。
                    - **没有回落**。模板引擎只能规划标题，写不了正文，所以模型不可用时直接 400 说明原因，
                      而不是返回一段占位符。

                    响应是 `text/event-stream`，每章开始前推一条 `progress`，结束时推一条 `result`：
                    ```
                    event: progress
                    data: {"index":1,"total":6,"title":"第一章 绪论"}

                    event: result
                    data: {"topic":"…","sections":[…],"references":[…],"wordCount":7800}
                    ```
                    失败时推 `event: error`，`data` 为 `{"message":"…"}`。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE 事件流"),
            @ApiResponse(responseCode = "400", description = "缺大纲 / 未配置模型 / 缺题目",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未登录（按 token 计费的链路需要账户）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "402", description = "New API 账户余额不足",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "中继凭据未就绪 / 计费网关不可用",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping(value = "/full-text/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateFullText(@Valid @RequestBody ThesisFullTextRequest request,
                                       HttpServletRequest httpRequest) {
        CurrentUser currentUser = optionalUser(httpRequest);
        AppSiteEntity site = channelResolver.resolve(httpRequest);
        Long userId = currentUser == null ? null : currentUser.userId();

        // 计费前置：先确认「谁来付、付不付得起」，再开流。
        // 放在这里（而不是第一章里）有两个原因：401/402/503 能以正常 HTTP 状态返回，
        // 前端可以直接提示「登录」或「充值」；也保证余额为零时不会先跑完文献检索、
        // 再在进度弹窗里失败，让用户白等一场。
        thesisService.checkFullTextBilling(currentUser);

        // 正文是长活（每章一次模型调用，通常 30-90 秒），超出 Servlet 容器默认的超时；
        // 这里给足一个完整生成的时间，客户端断开时会由 Spring 触发 onCompletion。
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(10).toMillis());

        AtomicReference<Future<?>> running = new AtomicReference<>();
        try {
            running.set(fullTextExecutor.submit(
                    () -> streamFullText(userId, site.getId(), request, currentUser, emitter)));
        } catch (RejectedExecutionException rejected) {
            // 队列满 = 现场正在写全文的用户已经排到上限，新请求立刻拿到 503，而不是无限排队。
            log.warn("Full text generation rejected: the executor queue is full");
            throw new ServiceUnavailableException("全文生成排队已满，请稍后重试");
        }

        // 连接结束必须终止任务：断开（onCompletion）/超时（onTimeout）/出错（onError）之后，
        // 这条线程还在按章调用模型 —— 没人收结果，token 却照扣。
        emitter.onCompletion(() -> cancelQuietly(running.get()));
        emitter.onTimeout(() -> {
            cancelQuietly(running.get());
            // 自己注册了超时回调，Spring 就不再替我们 complete，这里补上。
            emitter.complete();
        });
        emitter.onError(error -> cancelQuietly(running.get()));

        return emitter;
    }

    /** 全文生成的写流主体；在 {@link #fullTextExecutor} 的线程上跑。 */
    private void streamFullText(Long userId,
                                Long siteId,
                                ThesisFullTextRequest request,
                                CurrentUser currentUser,
                                SseEmitter emitter) {
        try {
            ThesisFullTextResponse result = thesisService.generateFullText(
                    userId, siteId, request, currentUser,
                    progress -> {
                        // 被取消（客户端断开）之后不要再写下一章：每章都是一次计费调用。
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IllegalStateException("客户端已断开，停止生成");
                        }
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("progress")
                                    .data(Map.of(
                                            "index", progress.index(),
                                            "total", progress.total(),
                                            "title", progress.title())));
                        } catch (IOException | IllegalStateException ignored) {
                            // 客户端断开时是 IOException；emitter 已 complete 时是
                            // IllegalStateException（Spring 用 assert 拦的）。
                            // 两种都只代表「这条推不出去」，不该中断章节生成。
                        }
                    });
            emitter.send(SseEmitter.event().name("result").data(result));
            emitter.complete();
        } catch (Exception exception) {
            log.warn("Full text generation failed: {}", exception.getMessage());
            /*
             * 错误事件是直接显示给用户看的：中文业务句原样保留（缺大纲 / 余额不足 /
             * 未配置模型都是我们写给用户的），纯英文的内部异常一律换成中文兜底。
             * 否则跨域、上游 5xx、Jackson 之类的原文会出现在进度弹窗里 ——
             * 实测过的原文长这样：「thesis document 999999 was not found」。
             */
            String message = GlobalExceptionHandler.keepCjkOr(
                    exception.getMessage(), "生成失败，请稍后重试");
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", message)));
                emitter.complete();
            } catch (IOException | IllegalStateException ignored) {
                // 连接已断或 emitter 已结束：错误推不出去，但事件已经收尾，不再 complete。
            }
        }
    }

    /** 取消（并中断）对应的生成任务；已经结束就不用管。 */
    private static void cancelQuietly(Future<?> task) {
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
    }

    /**
     * Resolves the caller without ever turning a public request into a 401.
     *
     * <p>大纲与目录是免费功能，游客本来就能用。上游的 {@code resolveOptional} 在令牌
     * 损坏或过期时直接失败，那会让一个拿着旧令牌的浏览器连免费的大纲都点不开 ——
     * 这里把鉴权失败折叠成「游客」，与「没有令牌」同一条路径处理。</p>
     *
     * <p>需要账户的链路（保存稿件、全文）自己会再校验一次，所以宽容不会让任何人多拿到权限。</p>
     */
    private CurrentUser optionalUser(HttpServletRequest request) {
        try {
            return currentUserResolver.resolveOptional(request);
        } catch (UnauthorizedException exception) {
            log.debug("Presented token could not be resolved, continuing as a guest: {}",
                    exception.getMessage());
            return null;
        }
    }
}
