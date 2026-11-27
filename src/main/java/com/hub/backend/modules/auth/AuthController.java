package com.hub.backend.modules.auth;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.UnauthorizedException;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.auth.dto.AuthTokenResponse;
import com.hub.backend.modules.auth.dto.AuthUserResponse;
import com.hub.backend.modules.auth.dto.CaptchaChallengeResponse;
import com.hub.backend.modules.auth.dto.ChangePasswordRequest;
import com.hub.backend.modules.auth.dto.ConfirmSessionPasswordRequest;
import com.hub.backend.modules.auth.dto.DeleteAccountRequest;
import com.hub.backend.modules.auth.dto.EmailCodeRequest;
import com.hub.backend.modules.auth.dto.LoginRequest;
import com.hub.backend.modules.auth.dto.RefreshTokenRequest;
import com.hub.backend.modules.auth.dto.RegisterRequest;
import com.hub.backend.modules.auth.dto.RegistrationOptionsResponse;
import com.hub.backend.modules.auth.dto.UpdateProfileRequest;
import com.hub.backend.modules.appconfig.ChannelResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Registration, sign in, token rotation and sign out.
 *
 * <p>The account is always scoped to the site resolved from the request, never to a client supplied
 * id (see {@code docs/ai1foo-rebuild-plan.md} §8.1).</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "登录鉴权", description = "注册、登录、令牌刷新、登出与当前账号")
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final ChannelResolver channelResolver;
    private final CurrentUserResolver currentUserResolver;
    private final ClientContextResolver clientContextResolver;
    private final SessionPasswordConfirmationService sessionPasswordConfirmation;
    private final EmailCodeRateLimiter emailCodeRateLimiter;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthService authService,
                          CaptchaService captchaService,
                          ChannelResolver channelResolver,
                          CurrentUserResolver currentUserResolver,
                          ClientContextResolver clientContextResolver,
                          SessionPasswordConfirmationService sessionPasswordConfirmation,
                          EmailCodeRateLimiter emailCodeRateLimiter,
                          LoginRateLimiter loginRateLimiter) {
        this.authService = authService;
        this.captchaService = captchaService;
        this.channelResolver = channelResolver;
        this.currentUserResolver = currentUserResolver;
        this.clientContextResolver = clientContextResolver;
        this.sessionPasswordConfirmation = sessionPasswordConfirmation;
        this.emailCodeRateLimiter = emailCodeRateLimiter;
        this.loginRateLimiter = loginRateLimiter;
    }

    @Operation(summary = "注册表单能力",
            description = """
                    注册页在渲染表单前调用，决定要不要显示「邮箱」与「邮箱验证码」。

                    是否要求邮箱由上游决定（`app.auth.email-verification.register-mode` 的 `AUTO`
                    跟随 New API `/api/status` 的 `email_verification`），浏览器无法自行判断。
                    没有这个接口，前端只有两种糟糕选择：永远显示一个用不上的邮箱框，或者等用户
                    点「获取验证码」失败一次才知道。

                    - `emailVerificationRequired`：与 `POST /auth/register` 的判定完全一致，
                      包含 `ON` 的强制与 `OFF` 的关闭；
                    - `emailCodeAvailable`：`POST /auth/email-code` 是否真能发信。与上一项**分开**
                      回答：`register-mode=ON` 而上游未开邮箱验证时会得到「要求邮箱但发不出码」，
                      属部署配置错误，前端应照常渲染邮箱字段、只禁用发码；
                    - `captchaRequired`：等价于 `GET /auth/captcha` 是否返回 `404`。

                    读取上游状态有 1 分钟缓存，接口本身很轻；上游不可达时沿用上一次结论。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "注册表单能力")
    })
    @GetMapping("/registration-options")
    public RegistrationOptionsResponse registrationOptions() {
        return authService.registrationOptions();
    }

    @Operation(summary = "获取注册验证码",
            description = """
                    返回一张 PNG 图形验证码（data URL 形式）和它的 `captchaId`。注册时必须原样回传
                    `captchaId` 与图上文字，且**每个验证码只能使用一次**：答案错误或过期都会作废，
                    需重新获取。`expiresInSeconds` 为有效期，默认 5 分钟。

                    该接口本身按来源 IP 限流，避免被拿去批量取图做识别训练。
                    验证码功能被 `app.captcha.enabled` 关闭时，本接口返回 `404`；前端据此隐藏验证码输入框。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "验证码"),
            @ApiResponse(responseCode = "404", description = "验证码功能已关闭",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "来源 IP 取图过于频繁，见 Retry-After",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/captcha")
    public CaptchaChallengeResponse captcha(HttpServletRequest httpRequest) {
        if (!captchaService.isEnabled()) {
            /*
             * Not "issued but unchecked": a 404 is exactly how the frontend detects a backend
             * without captcha support, so a disabled captcha and an old backend behave the same way
             * and the form simply renders without the field.
             */
            throw new NotFoundException("Captcha is disabled");
        }
        CaptchaService.Challenge challenge =
                captchaService.create(clientContextResolver.clientIp(httpRequest));
        return new CaptchaChallengeResponse(
                challenge.id(), challenge.image(), challenge.ttl().toSeconds());
    }

    @Operation(summary = "注册",
            description = """
                    在请求所属站点下创建账号并直接签发令牌。登录名在同一站点内唯一；
                    密码用 Argon2id 存储，接口不会回传任何密码相关字段。

                    注册同时会用**同一个用户名和密码**调用 New API 的公开注册接口（与 New API
                    注册页同一个端点，不需要管理员权限），因此那边必须允许公开注册。若 New API
                    开启了邮箱验证，本接口还要求 `email` 与 `emailCode`：验证码由
                    `POST /auth/email-code` 触发 New API 发送，**本站不生成也不保存**，注册时原样
                    转交上游校验，通过后才把 `emailVerified` 记为 true。
                    远端账号建好后按它返回的 user id 绑定，同一个凭据在两边都能登录。
                    `AUTH_NEW_API_REGISTRATION_ENABLED=false` 可退回纯本地注册。

                    为防脚本批量注册，需要先调用 `GET /auth/captcha` 取得验证码，并在请求体中带上
                    `captchaId` 与 `captchaCode`（不区分大小写）；验证码只能使用一次，错误或过期返回
                    `400`，需重新获取。另外，同一来源 IP 在 `app.rate-limit.registration.window` 内最多
                    尝试 `max-attempts-per-ip` 次，超出返回 `429` 并带 `Retry-After`。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "注册成功并已登录"),
            @ApiResponse(responseCode = "400", description = "登录名或密码不符合规则，或验证码缺失/错误/过期",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "登录名或邮箱已被占用（本地或 New API）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "来源 IP 注册过于频繁，见 Retry-After",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503",
                    description = "New API 不可用、已关闭公开注册或邮箱验证状态不一致，账号未创建",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/register")
    public AuthTokenResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        AppSiteEntity site = channelResolver.resolve(httpRequest);
        return authService.register(site.getId(), site.getSiteKey(), request,
                clientContextResolver.resolve(httpRequest, request.clientType()));
    }

    @Operation(summary = "发送邮箱验证码",
            description = """
                    在注册表单里点「获取验证码」时调用。**本站不生成也不保存验证码**：请求被转发给
                    New API 的公开验证码接口，邮件由 New API 配置的 SMTP 账号寄出，验证码留在
                    New API 的进程内存里。用户收到的 6 位数字要在 `POST /auth/register` 里原样回传，
                    由 New API 校验。

                    因此这个接口只在**本站已接入 New API 注册**且**上游打开了邮箱验证**时可用，其余情况
                    返回 `404` —— 与 `GET /auth/registration-options` 的 `emailCodeAvailable` 完全一致，
                    前端可以只信那个标志而不必试探本接口。邮箱已被占用的地址无法再次收到验证码
                    （上游行为）。

                    为免被用来轰炸他人邮箱，同一来源 IP 与同一邮箱各有独立配额，超出返回 `429`
                    并带 `Retry-After`。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已请求上游发送"),
            @ApiResponse(responseCode = "400", description = "邮箱格式不正确",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "上游未启用邮箱验证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "该邮箱已被占用",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "请求过于频繁，见 Retry-After",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "上游不可用或 SMTP 未配置",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/email-code")
    public ResponseEntity<Void> sendEmailCode(@Valid @RequestBody EmailCodeRequest request,
                                              HttpServletRequest httpRequest) {
        AppSiteEntity site = channelResolver.resolve(httpRequest);
        emailCodeRateLimiter.checkAndRecord(
                clientContextResolver.clientIp(httpRequest), request.email());
        authService.sendRegistrationEmailCode(site.getId(), request.email());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "登录",
            description = """
                    账号不存在与密码错误返回同一条 `401` 提示，且账号不存在时也执行一次 BCrypt 比对，
                    避免通过响应内容或响应时间枚举账号。

                    登录名与 New API 的用户名是同一个标识：若本站还没有该账号，但 New API 接受这组
                    凭据，会即时在本站开户并登录（`app.auth.new-api-account-provisioning-enabled`）。

                    失败次数超限返回 `429`（带 `Retry-After`）：按来源 IP 与登录名各记一把账，
                    只数凭据错误，登录成功会清零；提示语与账号是否存在无关。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登录成功"),
            @ApiResponse(responseCode = "400", description = "参数缺失或超长",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "登录名或密码错误",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "账号已被停用",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "失败次数过多，见 Retry-After",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/login")
    public AuthTokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        AppSiteEntity site = channelResolver.resolve(httpRequest);
        String clientIp = clientContextResolver.clientIp(httpRequest);
        // 失败限流：进门先看预算，凭据错了才记账，成功登录把该登录名的计数清零。
        // 计数与提示都不区分账号是否存在，因此不会成为枚举工具。
        loginRateLimiter.requireAllowed(clientIp, request.username());
        AuthTokenResponse tokens;
        try {
            tokens = authService.login(site.getId(), site.getSiteKey(), request,
                    clientContextResolver.resolve(httpRequest, request.clientType()));
        } catch (UnauthorizedException exception) {
            loginRateLimiter.recordFailure(clientIp, request.username());
            throw exception;
        }
        loginRateLimiter.recordSuccess(request.username());
        return tokens;
    }

    @Operation(summary = "刷新令牌",
            description = """
                    用 `refreshToken` 换取新的令牌对，并**轮换**会话：旧 refresh token 立即失效。
                    若已失效的 refresh token 被再次使用，视为泄漏，该账号的全部活跃会话会被撤销。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "刷新成功"),
            @ApiResponse(responseCode = "401", description = "refresh token 未知、过期或已被轮换",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "账号已被停用",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/refresh")
    public AuthTokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        return authService.refresh(request.refreshToken(), clientContextResolver.resolve(httpRequest, null));
    }

    @Operation(summary = "登出",
            description = "撤销当前访问令牌所在的会话，之后该令牌与配套 refresh token 都不再可用。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已登出"),
            @ApiResponse(responseCode = "401", description = "未携带有效访问令牌",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        authService.logout(currentUserResolver.requireClaims(httpRequest).sessionId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "当前账号",
            description = "需要 `Authorization: Bearer <accessToken>`；未登录或令牌失效返回 `401`。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "当前账号信息"),
            @ApiResponse(responseCode = "401", description = "未登录或令牌已失效",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/me")
    public AuthUserResponse me(HttpServletRequest httpRequest) {
        return authService.currentAccount(currentUserResolver.requireAuthenticated(httpRequest).userId());
    }

    @Operation(summary = "确认账号密码，补回本会话的登录口令",
            description = """
                    当其他接口返回 **428** 时调用：登录态本身有效，只是服务端本会话已不持有该账号的
                    明文密码（进程重启、内存条目过期都会这样），而某些操作需要以用户本人身份登录
                    New API。确认一次密码即可让原请求重试成功，**不需要退出登录**。

                    密码只写进服务端内存（新算一个有效期），绝不落库、不回显。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已确认，可以重试原请求"),
            @ApiResponse(responseCode = "400", description = "密码不正确",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未登录或令牌已失效",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "账号已被停用",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/session/password")
    public ResponseEntity<Void> confirmSessionPassword(
            @Valid @RequestBody ConfirmSessionPasswordRequest request,
            HttpServletRequest httpRequest) {
        sessionPasswordConfirmation.confirm(
                currentUserResolver.requireAuthenticated(httpRequest), request.password());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "更新账号资料",
            description = """
                    更新当前账号的昵称、绑定邮箱和绑定手机号。邮箱统一转为小写并按站点校验唯一性；
                    邮箱或手机号传空字符串表示解除绑定。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新后的账号资料"),
            @ApiResponse(responseCode = "400", description = "昵称、邮箱或手机号格式不正确",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未登录或令牌已失效",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "账号已被停用",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "邮箱或手机号已被其他账号绑定",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/profile")
    public AuthUserResponse updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            HttpServletRequest httpRequest) {
        TokenService.AccessTokenClaims claims = currentUserResolver.requireClaims(httpRequest);
        return authService.updateProfile(claims.userId(), request);
    }

    @Operation(summary = "上传头像",
            description = """
                    以 `multipart/form-data` 提交，字段名固定为 `file`。仅接受常见图片类型
                    （png / jpeg / gif / webp / bmp）。上传成功后写入账号 `avatarUrl` 并返回最新资料。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新后的账号资料"),
            @ApiResponse(responseCode = "400", description = "文件为空或不是允许的图片类型",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未登录或令牌已失效",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "账号已被停用",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "文件超过大小上限",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AuthUserResponse updateAvatar(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest httpRequest) {
        return authService.updateAvatar(currentUserResolver.requireAuthenticated(httpRequest), file);
    }

    @Operation(summary = "修改密码",
            description = """
                    校验当前密码后更新 BCrypt 密码哈希。修改成功后，当前会话保持登录，
                    该账号在其他设备上的活跃会话会立即撤销。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "密码已修改"),
            @ApiResponse(responseCode = "400", description = "新密码不符合规则",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未登录或当前密码错误",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "账号已被停用",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest) {
        TokenService.AccessTokenClaims claims = currentUserResolver.requireClaims(httpRequest);
        authService.changePassword(claims.userId(), claims.sessionId(), request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "注销账号",
            description = """
                    需要当前密码以及固定确认文字 `注销账号`。账号会立即停用、撤销全部会话并删除
                    登录身份；密码、邮箱、手机号、头像和昵称会被清除，历史业务数据保留但不再关联
                    可识别资料。
                    绑定的 New API 账户会一并删除：本站用当前密码登录 New API 后调用其删除接口，
                    上游账户及其全部密钥随之下线，用户名可重新注册。该调用需要上游的两步验证，
                    因此依赖本次输入的密码；若上游拒绝（例如该账户是 New API 超级管理员），
                    则退化为只撤销本站持有的中转密钥与 PAT，并记录告警日志。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "账号已注销"),
            @ApiResponse(responseCode = "400", description = "确认文字不正确",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未登录或密码错误",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "账号已被停用",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @DeleteMapping("/account")
    public ResponseEntity<Void> deleteAccount(
            @Valid @RequestBody DeleteAccountRequest request,
            HttpServletRequest httpRequest) {
        TokenService.AccessTokenClaims claims = currentUserResolver.requireClaims(httpRequest);
        authService.deleteAccount(claims.userId(), claims.sessionId(), request);
        return ResponseEntity.noContent().build();
    }
}
