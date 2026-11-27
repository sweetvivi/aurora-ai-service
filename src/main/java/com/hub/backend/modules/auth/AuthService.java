package com.hub.backend.modules.auth;

import com.hub.backend.common.config.properties.AuthProperties;
import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.ConflictException;
import com.hub.backend.common.exception.ForbiddenException;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.common.exception.UnauthorizedException;
import com.hub.backend.common.security.SessionPasswordVault;
import com.hub.backend.infra.newapi.NewApiLoginSession;
import com.hub.backend.infra.newapi.NewApiRejectedException;
import com.hub.backend.infra.newapi.NewApiRegistrationException;
import com.hub.backend.modules.auth.dto.AuthTokenResponse;
import com.hub.backend.modules.auth.dto.AuthUserResponse;
import com.hub.backend.modules.auth.dto.ChangePasswordRequest;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.auth.dto.DeleteAccountRequest;
import com.hub.backend.modules.auth.dto.LoginRequest;
import com.hub.backend.modules.auth.dto.RegisterRequest;
import com.hub.backend.modules.auth.dto.RegistrationOptionsResponse;
import com.hub.backend.modules.auth.dto.UpdateProfileRequest;
import com.hub.backend.modules.auth.entity.UserIdentityEntity;
import com.hub.backend.modules.auth.repository.UserIdentityRepository;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.gateway.NewApiAccountIdentityService;
import com.hub.backend.modules.gateway.UserGatewayTokenService;
import com.hub.backend.modules.user.AccountDataPurger;
import com.hub.backend.modules.user.UserService;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Registration, sign in, token rotation and sign out.
 *
 * <p>Implements {@code docs/ai1foo-rebuild-plan.md} §8.1: the account is scoped to the site
 * resolved from the request rather than from a client supplied id, passwords are hashed with
 * Argon2id, only refresh token hashes are stored, and rotation detects replay.</p>
 *
 * <p>Failed sign ins answer with one message whether the account is unknown or the password is
 * wrong, and an unknown account still performs a password comparison, so neither the body nor the
 * response time reveals which usernames exist.</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String INVALID_CREDENTIALS = "Incorrect username or password";

    /** 登录死锁重试次数：1 次首次 + 2 次重试，随后转 503。 */
    private static final int MAX_LOGIN_DEADLOCK_RETRIES = 2;

    /** MySQL 死锁错误码（InnoDB: Deadlock found when trying to get lock）。 */
    private static final int MYSQL_DEADLOCK_ERROR_CODE = 1213;

    /** 死锁的通用 SQLState（serialization failure）。 */
    private static final String DEADLOCK_SQL_STATE = "40001";

    private static final Set<String> AVATAR_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp");

    private final UserAccountRepository accountRepository;
    private final UserIdentityRepository identityRepository;
    private final AuthSessionService sessionService;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;
    private final UserService userService;
    private final RegistrationRateLimiter registrationRateLimiter;
    private final CaptchaService captchaService;
    private final UserGatewayTokenService gatewayTokenService;
    private final NewApiAccountIdentityService accountIdentity;
    private final SessionPasswordVault passwordVault;
    private final FileService fileService;
    private final AccountDataPurger accountDataPurger;

    /** Compared against when the account does not exist, purely to equalise response time. */
    private final String timingEqualisationHash;

    public AuthService(UserAccountRepository accountRepository,
                       UserIdentityRepository identityRepository,
                       AuthSessionService sessionService,
                       TokenService tokenService,
                       PasswordEncoder passwordEncoder,
                       AuthProperties authProperties,
                       UserService userService,
                       RegistrationRateLimiter registrationRateLimiter,
                       CaptchaService captchaService,
                       UserGatewayTokenService gatewayTokenService,
                       NewApiAccountIdentityService accountIdentity,
                       SessionPasswordVault passwordVault,
                       FileService fileService,
                       AccountDataPurger accountDataPurger) {
        this.accountRepository = accountRepository;
        this.identityRepository = identityRepository;
        this.sessionService = sessionService;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.authProperties = authProperties;
        this.userService = userService;
        this.registrationRateLimiter = registrationRateLimiter;
        this.captchaService = captchaService;
        this.gatewayTokenService = gatewayTokenService;
        this.accountIdentity = accountIdentity;
        this.passwordVault = passwordVault;
        this.fileService = fileService;
        this.accountDataPurger = accountDataPurger;
        this.timingEqualisationHash = passwordEncoder.encode("timing-equalisation-placeholder");
    }

    @Transactional
    public AuthTokenResponse register(Long siteId, String siteKey, RegisterRequest request, ClientContext context) {
        // First thing, before any hashing or writes: registration is the cheapest endpoint to abuse.
        registrationRateLimiter.checkAndRecord(context.ipAddress());

        /*
         * Captcha second, still before the existence check and BCrypt: a wrong answer must not reach
         * the database or the password hasher. It is verified *after* the address counter on purpose
         * — a captcha failure is an attempt too, and letting it skip the counter would hand a script
         * unlimited guesses against one issued image.
         */
        captchaService.requireValid(request.captchaId(), request.captchaCode());

        String username = request.username().strip();
        PasswordPolicy.requireValid(request.password(), "密码");

        /*
         * Whether an address belongs to the registration contract is decided upstream, not here.
         * New API verifies the code and only then writes users.email, so following its
         * /api/status keeps both sides in step without an operator flipping two switches.
         */
        boolean emailRequired = emailVerificationRequiredForRegistration();
        String email = normalizeEmail(request.email());
        String emailCode = normalizeNullable(request.emailCode());
        if (emailRequired) {
            if (!authProperties.isNewApiRegistrationEnabled()) {
                throw new ServiceUnavailableException("账号服务未启用，无法完成邮箱验证");
            }
            if (email == null) {
                throw new InvalidRequestException("请填写邮箱地址");
            }
            if (emailCode == null) {
                throw new InvalidRequestException("请填写邮箱验证码");
            }
        }
        if (email != null && accountRepository.existsBySiteIdAndEmail(siteId, email)) {
            throw new ConflictException("该邮箱已绑定其他账号");
        }

        if (accountRepository.existsBySiteIdAndUsername(siteId, username)
                || identityRepository.existsByProviderAndProviderSubject(
                        UserIdentityEntity.PROVIDER_PASSWORD,
                        UserIdentityEntity.passwordSubject(siteKey, username))) {
            throw new ConflictException("用户名「%s」已被占用".formatted(username));
        }

        String nickname = request.nickname() == null || request.nickname().isBlank()
                ? username
                : request.nickname().strip();

        /*
         * The New API account is created before the local row, through New API's own public sign-up
         * endpoint — the only write that needs no administrator. Order matters: a local account with
         * no New API identity behind it registers fine and then fails at the first AI call, because
         * every generation signs in to New API as that user. Failing here instead leaves nothing
         * half-created.
         */
        NewApiLoginSession remote = authProperties.isNewApiRegistrationEnabled()
                ? registerOnNewApi(username, request.password(), email, emailCode)
                : null;

        UserAccountEntity account = accountRepository.save(new UserAccountEntity(siteId, username, nickname));
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setEmail(email);
        if (emailRequired) {
            /*
             * The upstream code was accepted on this exact address, which is the only proof of
             * ownership this site can obtain: it never sends mail of its own.
             */
            account.markEmailVerified();
        }
        account.markLoggedIn(LocalDateTime.now());

        identityRepository.save(new UserIdentityEntity(
                account.getId(),
                UserIdentityEntity.PROVIDER_PASSWORD,
                UserIdentityEntity.passwordSubject(siteKey, username)));

        if (remote != null) {
            // A registration that proved the remote account is this user's is the strongest
            // ownership evidence there is, so the binding is marked trusted.
            accountIdentity.bindIdentity(account.getId(), siteId, username, remote.userId(), true);
        }

        passwordVault.remember(account.getId(), request.password());
        return issueTokens(account, context);
    }

    /**
     * Creates the New API account with the public registration endpoint and returns its session.
     *
     * <p>The local hash is the only password this site stores; New API keeps its own. They are two
     * hashes of the same plaintext, which is what makes one credential work on both sides.</p>
     */
    private NewApiLoginSession registerOnNewApi(String username,
                                                String password,
                                                String email,
                                                String emailCode) {
        try {
            return accountIdentity.registerAccount(username, password, email, emailCode);
        } catch (NewApiRegistrationException refused) {
            switch (refused.kind()) {
                case ALREADY_EXISTS:
                    return claimRemoteAccount(username, password);
                case EMAIL_TAKEN:
                    throw new ConflictException("该邮箱已被其他账号使用");
                case VERIFICATION_CODE_INVALID:
                    throw new InvalidRequestException("邮箱验证码不正确或已过期，请重新获取");
                case EMAIL_VERIFICATION_REQUIRED:
                    // Only reachable while the two sides disagree about the setting, or while the
                    // cached /api/status still says "off" right after the operator flipped it.
                    throw new ServiceUnavailableException("注册需要邮箱验证，请稍后重试");
                case DISABLED:
                    throw new ServiceUnavailableException("账号服务已关闭注册，请联系管理员");
                default:
                    throw new ServiceUnavailableException("创建账号失败，请稍后重试");
            }
        } catch (ServiceUnavailableException unavailable) {
            // 这些文案会原样显示在注册页：用户看不懂「New API integration is disabled」，
            // 也不知道该找谁。把「没开启」和「连不上」分开说清楚。
            String message = unavailable.getMessage() == null ? "" : unavailable.getMessage();
            if (message.contains("New API integration is disabled")) {
                throw new ServiceUnavailableException("账号服务未启用，请联系管理员");
            }
            throw unavailable;
        } catch (RuntimeException failed) {
            log.warn("New API registration for '{}' failed: {}", username, failed.toString());
            throw new ServiceUnavailableException(
                    "创建账号失败，请稍后重试");
        }
    }

    /**
     * Handles a name New API already knows.
     *
     * <p>Two cases look identical from the refusal alone: an earlier attempt created the remote
     * account but the local row was never written, or the name belongs to somebody else. Only the
     * password separates them, so it is checked against New API once. A wrong password stays a
     * conflict — it must never fall through to a locally created account that cannot use AI.</p>
     */
    private NewApiLoginSession claimRemoteAccount(String username, String password) {
        try {
            return accountIdentity.loginWithCandidate(username, password);
        } catch (RuntimeException rejected) {
            throw new ConflictException(
                    "用户名「%s」已被占用".formatted(username));
        }
    }

    /**
     * Asks New API to mail a sign-up code to the address.
     *
     * <p>This site produces no code of its own: the call is forwarded, the mail is sent by the
     * upstream SMTP account, and the code stays in New API's memory until the registration
     * consumes it. Nothing is written here, which is why there is no code table to clean up.</p>
     *
     * <p>The same two conditions {@link #registrationOptions()} reports as
     * {@code emailCodeAvailable}: with the integration switched off no registration can consume a
     * code, and an upstream that does not verify addresses has no endpoint to forward to. Keeping
     * them in step is what lets the client trust that flag instead of probing this endpoint.</p>
     *
     * @throws NotFoundException when registration is local-only, or the upstream deployment does
     *                          not use email verification
     */
    @Transactional(readOnly = true)
    public void sendRegistrationEmailCode(Long siteId, String email) {
        String address = normalizeEmail(email);
        if (address == null || !isEmailCodeAvailable()) {
            throw new NotFoundException("邮箱验证未启用");
        }
        if (accountRepository.existsBySiteIdAndEmail(siteId, address)) {
            throw new ConflictException("该邮箱已绑定其他账号");
        }
        try {
            accountIdentity.sendVerificationCode(address);
        } catch (NewApiRejectedException rejected) {
            String upstream = rejected.getMessage() == null
                    ? ""
                    : rejected.getMessage().toLowerCase(Locale.ROOT);
            if (upstream.contains("already in use")
                    || upstream.contains("已被")
                    || upstream.contains("占用")) {
                throw new ConflictException("该邮箱已被其他账号使用");
            }
            log.warn("New API refused a verification code for '{}': {}", address, upstream);
            throw new ServiceUnavailableException("验证码发送失败，请稍后重试");
        } catch (RuntimeException failure) {
            log.warn("Sending a verification code to '{}' failed: {}", address, failure.toString());
            throw new ServiceUnavailableException("验证码发送失败，请稍后重试");
        }
    }

    /**
     * 登录。
     *
     * <p><b>故意不加 {@code @Transactional}</b>：同一个事务里既改 {@code user_accounts}（
     * last_login_at）又插 {@code auth_sessions}，同账号并发登录时两条写集互相等锁，
     * InnoDB 直接报 1213 回滚（压测实测 168 次，登录接口 500 率 3%）。现在这两步是各自提交的
     * 短事务，锁序不再交叉：先单行更新账号，提交释放之后才去建会话。</p>
     */
    public AuthTokenResponse login(Long siteId,
                                   String siteKey,
                                   LoginRequest request,
                                   ClientContext context) {
        String username = request.username().strip();
        PasswordPolicy.requireValid(request.password(), "密码");
        UserAccountEntity account = accountRepository.findFirstBySiteIdAndUsername(siteId, username).orElse(null);

        if (account == null) {
            return loginIntoNewAccount(siteId, siteKey, username, request, context);
        }
        if (!account.isActive()) {
            throw new ForbiddenException(ApiMessages.ACCOUNT_DISABLED);
        }
        if (!verifyLocalPassword(account, request.password())) {
            throw new UnauthorizedException(INVALID_CREDENTIALS);
        }
        requireVerifiedEmailForLogin(account);

        passwordVault.remember(account.getId(), request.password());
        return completeLogin(account, context);
    }

    /**
     * 登录的落库收尾：先单行更新 last_login_at，再单独建会话。
     *
     * <p>两步分开提交是这里的存在理由，见 {@link #login}。改成两步之后死锁概率大幅下降，但并发够高时
     * 仍可能撞上（别的路径也在写 user_accounts），所以对「确实被判为死锁」的失败做有限次重试：
     * 每步各自是短事务，回滚后重做是幂等的（last_login_at 覆盖写，会话行要么没落地要么已提交）。</p>
     *
     * <p>只重试死锁（SQLState 40001 / MySQL 1213），不重试等锁超时 —— 那种情况重试只会把 50 秒
     * 的等待再来一遍。</p>
     */
    private AuthTokenResponse completeLogin(UserAccountEntity account, ClientContext context) {
        for (int attempt = 0; ; attempt++) {
            try {
                LocalDateTime now = LocalDateTime.now();
                account.markLoggedIn(now);
                // 显式更新：本方法没有外层事务，脏检查不会替我们把这一行写回去。
                accountRepository.touchLastLogin(account.getId(), now);
                return issueTokens(account, context);
            } catch (RuntimeException failure) {
                if (!isDeadlock(failure)) {
                    throw failure;
                }
                if (attempt >= MAX_LOGIN_DEADLOCK_RETRIES) {
                    log.warn("Login for account {} is still deadlocked after {} retries: {}",
                            account.getId(), attempt, failure.getMessage());
                    throw new ServiceUnavailableException("登录服务繁忙，请稍后重试");
                }
                log.warn("Login for account {} lost a deadlock; retry {}/{}: {}",
                        account.getId(), attempt + 1, MAX_LOGIN_DEADLOCK_RETRIES, failure.getMessage());
                backOffBeforeRetry(attempt);
            }
        }
    }

    /** 死锁判定：Spring 的 DeadlockLoserDataAccessException，或底层 SQLState 40001 / MySQL 1213。 */
    private static boolean isDeadlock(Throwable failure) {
        for (Throwable current = failure;
             current != null && current.getCause() != current;
             current = current.getCause()) {
            if (current instanceof DeadlockLoserDataAccessException) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                if (sqlException.getErrorCode() == MYSQL_DEADLOCK_ERROR_CODE
                        || DEADLOCK_SQL_STATE.equals(sqlException.getSQLState())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 指数退避：100ms、200ms。重试次数很小，不需要更复杂的抖动。 */
    private static void backOffBeforeRetry(int attempt) {
        try {
            Thread.sleep(100L * (1L << attempt));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("登录服务繁忙，请稍后重试");
        }
    }

    /**
     * Signs in a name this site has never seen, by asking New API who it is.
     *
     * <p>This is the direction that used to be impossible: an account registered on New API could
     * not use this site at all, because sign in started from {@code user_accounts}. Now the name is
     * the shared identity, so a name New API accepts is a name that may open an account here.</p>
     *
     * <p>The local account is created only after New API confirms the credentials. Only the local
     * Argon2id hash is retained.</p>
     */
    private AuthTokenResponse loginIntoNewAccount(Long siteId,
                                                 String siteKey,
                                                 String username,
                                                 LoginRequest request,
                                                 ClientContext context) {
        if (!authProperties.isNewApiAccountProvisioningEnabled()) {
            passwordEncoder.matches(request.password(), timingEqualisationHash);
            throw new UnauthorizedException(INVALID_CREDENTIALS);
        }
        /*
         * A name provisioned here has never been through an email check, and this site cannot
         * perform one on its own. When verified addresses are required, this path cannot satisfy
         * that requirement, so it answers the same way as a missing one instead of creating an
         * account that can never sign in.
         */
        if (loginRequiresVerifiedEmail()) {
            passwordEncoder.matches(request.password(), timingEqualisationHash);
            throw new ForbiddenException("账号邮箱尚未验证，请先注册并完成邮箱验证");
        }

        NewApiLoginSession login;
        try {
            login = accountIdentity.loginWithCandidate(username, request.password());
        } catch (RuntimeException exception) {
            log.debug("New API did not provision account '{}': {}", username, exception.toString());
            passwordEncoder.matches(request.password(), timingEqualisationHash);
            throw new UnauthorizedException(INVALID_CREDENTIALS);
        }

        /*
         * 这里没有外层事务了，所以字段先设好再一次性插入：以前是「插入后再改 hash，靠脏检查回写」，
         * 没有事务时那样写会静默丢掉哈希。
         */
        UserAccountEntity account = new UserAccountEntity(siteId, username, username);
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.markLoggedIn(LocalDateTime.now());
        account = accountRepository.save(account);
        identityRepository.save(new UserIdentityEntity(
                account.getId(),
                UserIdentityEntity.PROVIDER_PASSWORD,
                UserIdentityEntity.passwordSubject(siteKey, username)));
        accountIdentity.bindIdentity(account.getId(), siteId, username, login.userId(), true);
        passwordVault.remember(account.getId(), request.password());
        return completeLogin(account, context);
    }

    /** The local password hash is the only authority for an existing account. */
    private boolean verifyLocalPassword(UserAccountEntity account, String password) {
        String hash = account.getPasswordHash();
        if (hash == null || !passwordEncoder.matches(password, hash)) {
            return false;
        }
        if (passwordEncoder.upgradeEncoding(hash)) {
            account.setPasswordHash(passwordEncoder.encode(password));
            accountRepository.save(account);
        }
        return true;
    }

    /**
     * Exchanges a refresh token for a new token pair, rotating the session.
     *
     * @throws UnauthorizedException when the token is unknown, expired or already rotated
     */
    @Transactional
    public AuthTokenResponse refresh(String refreshToken, ClientContext context) {
        AuthSessionService.IssuedSession session = sessionService.rotate(
                refreshToken, context.userAgent(), context.ipAddress());

        UserAccountEntity account = userService.requireById(session.session().getUserId());
        if (!account.isActive()) {
            throw new ForbiddenException(ApiMessages.ACCOUNT_DISABLED);
        }
        return buildResponse(account, session);
    }

    @Transactional
    public void logout(Long sessionId) {
        sessionService.revokeSession(sessionId);
    }

    @Transactional
    public AuthUserResponse updateProfile(Long userId, UpdateProfileRequest request) {
        UserAccountEntity account = requireActiveAccount(userId);
        String nickname = request.nickname().strip();
        String email = normalizeEmail(request.email());
        String phone = normalizeNullable(request.phone());

        if (email != null
                && accountRepository.existsBySiteIdAndEmailAndIdNot(account.getSiteId(), email, userId)) {
            throw new ConflictException("该邮箱已绑定其他账号");
        }
        if (phone != null
                && accountRepository.existsBySiteIdAndPhoneAndIdNot(account.getSiteId(), phone, userId)) {
            throw new ConflictException("该手机号已绑定其他账号");
        }

        account.setNickname(nickname);
        boolean emailChanged = !Objects.equals(email, account.getEmail());
        account.setEmail(email);
        if (emailChanged) {
            /*
             * A self-declared address is not a proved one. Whatever the upstream confirmed before
             * refers to the old address, so the flag has to go with it — otherwise editing this
             * field would be a way to take over another person's address and claim it as verified.
             */
            account.clearEmailVerified();
        }
        account.setPhone(phone);
        return AuthUserResponse.from(accountRepository.save(account));
    }

    /**
     * Uploads an image as the current account's avatar and stores the file content URL.
     */
    @Transactional
    public AuthUserResponse updateAvatar(CurrentUser currentUser, MultipartFile file) {
        UserAccountEntity account = requireActiveAccount(currentUser.userId());
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("请选择要上传的图片");
        }
        String contentType = file.getContentType();
        String normalizedType = contentType == null
                ? ""
                : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        if (!AVATAR_CONTENT_TYPES.contains(normalizedType)) {
            throw new InvalidRequestException(
                    "头像仅支持 png、jpeg、gif、webp、bmp 格式");
        }

        UploadedFileResponse uploaded = fileService.upload(
                currentUser, file, null, FileAssetType.AVATAR);
        account.setAvatarUrl(uploaded.url());
        return AuthUserResponse.from(accountRepository.save(account));
    }

    @Transactional
    public void changePassword(Long userId,
                               Long currentSessionId,
                               ChangePasswordRequest request) {
        UserAccountEntity account = requireActiveAccount(userId);
        PasswordPolicy.requireValid(request.currentPassword(), "当前密码");
        requirePassword(account, request.currentPassword());
        PasswordPolicy.requireValid(request.newPassword(), "新密码");

        account.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        accountRepository.save(account);
        // The held password is what opens New API; keeping the old one would fail every AI call
        // until the next sign in.
        passwordVault.remember(userId, request.newPassword());
        sessionService.revokeOtherSessions(userId, currentSessionId);
    }

    @Transactional
    public void deleteAccount(Long userId,
                              Long currentSessionId,
                              DeleteAccountRequest request) {
        UserAccountEntity account = requireActiveAccount(userId);
        if (!"注销账号".equals(request.confirmation().strip())) {
            throw new InvalidRequestException("请输入「注销账号」以确认");
        }
        PasswordPolicy.requireValid(request.password(), "密码");
        requirePassword(account, request.password());

        // The password just typed is the only copy this server will ever see in plaintext, and
        // deleting the New API account needs it: New API guards that call with a password proof,
        // and the vault entry may have already expired for a long-lived session. Remembering it
        // here also covers the case where the sign-in predates a restart.
        passwordVault.remember(userId, request.password());

        gatewayTokenService.cleanupAccountAccess(CurrentUser.of(account));
        passwordVault.forget(userId);
        sessionService.revokeAllSessions(userId);
        identityRepository.deleteByUserId(userId);
        // The row is removed outright instead of being anonymised. Keeping a DISABLED
        // placeholder meant the username stayed reserved forever, so the owner could never
        // register the same name again. Child rows go first: their foreign keys are RESTRICT,
        // and the account row cannot be deleted while any of them still point at it.
        accountDataPurger.purge(userId);
        accountRepository.delete(account);
    }

    @Transactional(readOnly = true)
    public AuthUserResponse currentAccount(Long userId) {
        return AuthUserResponse.from(userService.requireById(userId));
    }

    /**
     * Turns verified access token claims into an account.
     *
     * <p>The session is re-checked on every request so sign out takes effect immediately instead of
     * at token expiry, and the account is re-read so a disabled account stops working at once.</p>
     *
     * @throws UnauthorizedException when the session was revoked, expired or belongs to another user
     */
    @Transactional(readOnly = true)
    public CurrentUser resolveCurrentUser(TokenService.AccessTokenClaims claims) {
        var session = sessionService.requireActiveSession(claims.sessionId());
        if (!session.getUserId().equals(claims.userId())) {
            throw new UnauthorizedException("Access token does not match its session");
        }

        UserAccountEntity account = userService.requireById(claims.userId());
        if (!account.isActive()) {
            throw new ForbiddenException(ApiMessages.ACCOUNT_DISABLED);
        }
        return CurrentUser.of(account);
    }

    private AuthTokenResponse issueTokens(UserAccountEntity account, ClientContext context) {
        AuthSessionService.IssuedSession session = sessionService.startSession(
                account.getId(), context.clientType(), context.userAgent(), context.ipAddress());
        return buildResponse(account, session);
    }

    private AuthTokenResponse buildResponse(UserAccountEntity account, AuthSessionService.IssuedSession session) {
        TokenService.IssuedToken accessToken = tokenService.issueAccessToken(
                account.getId(), session.session().getId());

        return AuthTokenResponse.of(
                accessToken.token(),
                authProperties.getAccessTokenTtl().toSeconds(),
                session.refreshToken(),
                authProperties.getRefreshTokenTtl().toSeconds(),
                AuthUserResponse.from(account));
    }

    private boolean loginRequiresVerifiedEmail() {
        return authProperties.getEmailVerification().isLoginRequiresVerifiedEmail();
    }

    private void requireVerifiedEmailForLogin(UserAccountEntity account) {
        if (!loginRequiresVerifiedEmail()) {
            return;
        }
        if (!account.isEmailVerified()) {
            throw new ForbiddenException("账号邮箱尚未验证，无法登录；请先完成邮箱验证");
        }
    }

    /**
     * What the registration form may render, asked before the user has typed anything.
     *
     * <p>Answers the same questions {@link #register} answers, but up front: the browser cannot
     * read the upstream flag itself, and learning it from a failed "send code" click means showing
     * an address box the deployment will never use.</p>
     *
     * <p>{@code emailCodeAvailable} is kept separate from {@code emailVerificationRequired} because
     * {@code register-mode=ON} can demand an address while the upstream cannot mail a code — an
     * operator mistake worth surfacing instead of hiding.</p>
     *
     * <p>Deliberately not transactional: no row is read, and the upstream status call must not pin a
     * database connection for the length of an HTTP round trip.</p>
     */
    public RegistrationOptionsResponse registrationOptions() {
        return new RegistrationOptionsResponse(
                emailVerificationRequiredForRegistration(),
                isEmailCodeAvailable(),
                captchaService.isEnabled());
    }

    /**
     * Whether {@code POST /auth/email-code} would actually deliver a code.
     *
     * <p>Both halves are needed: this site only forwards the request, so with the integration
     * switched off there is no registration path to consume a code, and an upstream that does not
     * verify addresses has nothing to forward to. It is deliberately not derived from
     * {@link #emailVerificationRequiredForRegistration()}: {@code register-mode=ON} keeps demanding
     * an address even when the upstream cannot mail one, and that gap is exactly what the client
     * needs to see rather than have hidden from it.</p>
     */
    private boolean isEmailCodeAvailable() {
        return authProperties.isNewApiRegistrationEnabled()
                && accountIdentity.isEmailVerificationEnabled();
    }

    /**
     * Whether this registration has to carry an address and an upstream code.
     *
     * <p>{@code AUTO} follows {@code GET /api/status}, so the site starts asking the moment the
     * upstream deployment does. An unreachable upstream keeps the last known answer instead of
     * silently dropping the requirement.</p>
     */
    private boolean emailVerificationRequiredForRegistration() {
        /*
         * A local-only registration has nothing to verify against and no upstream to ask, so it
         * must not reach the integration at all: that keeps AUTH_NEW_API_REGISTRATION_ENABLED=false
         * a genuinely offline switch instead of one that still calls New API for a flag.
         */
        if (!authProperties.isNewApiRegistrationEnabled()) {
            return false;
        }
        return authProperties.getEmailVerification()
                .requiresRegistrationEmail(accountIdentity.isEmailVerificationEnabled());
    }

    private UserAccountEntity requireActiveAccount(Long userId) {
        UserAccountEntity account = userService.requireById(userId);
        if (!account.isActive()) {
            throw new ForbiddenException(ApiMessages.ACCOUNT_DISABLED);
        }
        return account;
    }

    private void requirePassword(UserAccountEntity account, String password) {
        String hash = account.getPasswordHash();
        if (hash == null || !passwordEncoder.matches(password, hash)) {
            throw new UnauthorizedException(ApiMessages.CURRENT_PASSWORD_INCORRECT);
        }
        if (passwordEncoder.upgradeEncoding(hash)) {
            account.setPasswordHash(passwordEncoder.encode(password));
            accountRepository.save(account);
        }
    }

    private static String normalizeEmail(String value) {
        String email = normalizeNullable(value);
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
