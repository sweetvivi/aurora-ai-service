package com.hub.backend.modules.gateway.deletion;

import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.infra.newapi.NewApiRetryPolicy;
import com.hub.backend.modules.gateway.crypto.AesGcmTokenCipher;
import com.hub.backend.modules.gateway.entity.NewApiDeletionTaskEntity;
import com.hub.backend.modules.gateway.repository.NewApiDeletionTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 注销时没删掉的 New API 账号，记成一条待办，等限流窗口过去接着删。
 *
 * <p>为什么要有队列：New API 对 {@code /api/verify} 与一批关键接口做 20 次 / 20 分钟的限流，
 * 批量注销时 429 几乎是必然。撞上之后本地注销仍然要成功（用户的账号已经删了，不能因为上游
 * 忙就回滚），但远端那一行不能就这么留在上游：它带着余额、带着用户名，而那个用户名会被
 * 永久占用（{@code CheckUserExistOrDeleted} 对软删行也返回「已存在」）。所以失败的那一次
 * 必须留下可重放的凭据，交给 {@link NewApiAccountDeletionWorker} 之后继续删。</p>
 *
 * <p>三条刻意的取舍：</p>
 * <ul>
 *   <li>入队跟随调用方的事务（不另开新事务）。注销回滚时这条待办一起回滚，不会出现
 *       「本地还在、远端却被删了」这种最坏情况。</li>
 *   <li>密码用 AES-GCM 加密后存下来，且只给自助路径（{@link NewApiDeletionTaskEntity#MODE_SELF}）
 *       存。管理员路径只需要一个 id，多存一份密码只是多一份风险。</li>
 *   <li>重试用指数退避而不是固定间隔：限流窗口是 20 分钟，60 秒后重试只会再吃一个 429，
 *       把重试次数白白耗在窗口里面。</li>
 * </ul>
 */
@Service
public class NewApiAccountDeletionQueue {

    private static final Logger log = LoggerFactory.getLogger(NewApiAccountDeletionQueue.class);

    private static final int MAX_ERROR_LENGTH = 512;
    private static final Pattern API_KEY_PATTERN =
            Pattern.compile("(?i)(sk-[A-Za-z0-9_-]{6,})");
    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(?i)(Bearer\\s+)\\S+");

    private final NewApiDeletionTaskRepository tasks;
    private final NewApiProperties properties;
    private final AesGcmTokenCipher cipher;
    private final Clock clock;

    public NewApiAccountDeletionQueue(NewApiDeletionTaskRepository tasks,
                                      NewApiProperties properties,
                                      AesGcmTokenCipher cipher,
                                      Clock clock) {
        this.tasks = tasks;
        this.properties = properties;
        this.cipher = cipher;
        this.clock = clock;
    }

    /**
     * 把一次「远端账号没删掉」记成待办。
     *
     * <p>跟随调用方事务，所以写失败会连注销一起回滚 —— 那是想要的结果：注销没完成，
     * 就不该留下一条指向半途状态的任务。</p>
     *
     * @param localUserId  本地用户 id，只用于追溯与幂等
     * @param newapiUserId 远端 id；自助路径可能还不知道，可以传 {@code null}
     * @param username     远端用户名；自助路径登录要用
     * @param password     明文密码，仅自助路径需要；传 {@code null} 表示不需要
     * @param mode         {@link NewApiDeletionTaskEntity#MODE_ADMIN} 或
     *                     {@link NewApiDeletionTaskEntity#MODE_SELF}
     * @param reason       这一次失败的原因，落进 {@code last_error} 方便事后看
     * @return true 表示排队成功（或这个用户本来就已经在队列里）
     */
    @Transactional
    public boolean enqueue(Long localUserId,
                           Long newapiUserId,
                           String username,
                           String password,
                           String mode,
                           String reason) {
        if (!properties.isEnabled() || !properties.isDeletionRetryEnabled()) {
            return false;
        }
        if (localUserId == null) {
            return false;
        }
        if (newapiUserId == null && (username == null || username.isBlank())) {
            // 既没有远端 id 也没有用户名，重试时无法定位到任何一个账号。
            return false;
        }
        if (tasks.findFirstByLocalUserId(localUserId).isPresent()) {
            // 同一个用户只排一次。并发注销/重试都可能走到这里，重复排队没有意义。
            return true;
        }

        NewApiDeletionTaskEntity task = new NewApiDeletionTaskEntity(
                localUserId, newapiUserId, username, mode, now());
        task.noteInitialFailure(limit(reason));
        if (password != null && !password.isBlank()
                && NewApiDeletionTaskEntity.MODE_SELF.equals(mode)) {
            try {
                AesGcmTokenCipher.EncryptedToken sealed = cipher.encrypt(password);
                task.sealPassword(sealed.ciphertext(), sealed.nonce(), sealed.tag());
            } catch (RuntimeException exception) {
                // 没有可用的加密密钥（部署没配 NEW_API_ENCRYPTION_KEY）：这份密码没法安全地
                // 留到重试的时候。宁可不排队，也不明文落库。
                log.warn("Cannot queue the New API deletion for user {}: the password cannot be "
                        + "sealed ({})", localUserId, safeError(exception));
                return false;
            }
        }
        tasks.save(task);
        log.info("Queued New API account deletion for user {} (mode={}, newapiUserId={}): {}",
                localUserId, mode, newapiUserId, limit(reason));
        return true;
    }

    /**
     * 认领到点的任务。
     *
     * <p>先查后逐行条件更新：多实例同时扫到同一行时，只有一个能把状态从 PENDING 翻成 RUNNING，
     * 另一个拿到 0 行直接跳过。租约过期的 RUNNING 也在这里被接手，避免实例被杀之后那行永远挂着。</p>
     */
    @Transactional
    public List<NewApiDeletionTaskEntity> claimDue() {
        int batch = Math.max(1, properties.getDeletionSweepBatchSize());
        LocalDateTime now = now();
        LocalDateTime leaseUntil = now.plus(properties.getDeletionTaskLease());
        String owner = UUID.randomUUID().toString();

        List<NewApiDeletionTaskEntity> due = tasks.findDue(now, PageRequest.of(0, batch));
        if (due.isEmpty()) {
            return List.of();
        }
        List<NewApiDeletionTaskEntity> claimed = new ArrayList<>(due.size());
        for (NewApiDeletionTaskEntity task : due) {
            if (tasks.claim(task.getId(), owner, leaseUntil, now) == 1) {
                claimed.add(task);
            }
        }
        return claimed;
    }

    /** 删成了：整行消失。注销成功本身已经记在审计与访问日志里，这里不留历史。 */
    @Transactional
    public void complete(Long taskId) {
        tasks.deleteById(taskId);
    }

    /**
     * 这次仍然没删成：是上游忙就排下一次，是次数用尽就不再排。
     *
     * <p>失败原因写进 {@code last_error}，被脱敏后截断 —— 上游的报错里偶尔会带 key。</p>
     */
    @Transactional
    public void postpone(Long taskId, RuntimeException failure) {
        NewApiDeletionTaskEntity task = tasks.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        String error = safeError(failure);
        if (!NewApiRetryPolicy.isRetryable(failure)) {
            // 不是「忙」，是「不行」：密码改了、账号被禁用、root 不能删自己。再试也一样。
            task.giveUp(error);
            tasks.save(task);
            log.error("Giving up on the New API account deletion for user {} (mode={}): {}",
                    task.getLocalUserId(), task.getMode(), error);
            return;
        }
        if (task.getAttempts() + 1 >= Math.max(1, properties.getDeletionRetryMaxAttempts())) {
            task.giveUp(error);
            tasks.save(task);
            log.error("New API account deletion for user {} still fails after {} attempts, "
                    + "leaving the row for inspection: {}",
                    task.getLocalUserId(), task.getAttempts() + 1, error);
            return;
        }
        LocalDateTime nextAttemptAt = now().plus(delayAfter(task.getAttempts()));
        task.reschedule(nextAttemptAt, error);
        tasks.save(task);
        log.warn("New API account deletion for user {} failed, retrying at {} (attempt {}): {}",
                task.getLocalUserId(), nextAttemptAt, task.getAttempts(), error);
    }

    /**
     * 取出这条任务重放时要用的密码。
     *
     * @throws ServiceUnavailableException 没有存密码（管理员路径）或密文坏了
     */
    public String passwordOf(NewApiDeletionTaskEntity task) {
        if (!task.hasSealedPassword()) {
            throw new ServiceUnavailableException(
                    "The queued New API account deletion has no password to replay");
        }
        return cipher.decrypt(
                task.getPasswordCiphertext(), task.getPasswordNonce(), task.getPasswordTag());
    }

    /**
     * 指数退避：base × 2^已重试次数，封顶 {@code deletion-retry-max-delay}。
     *
     * <p>指数从第 0 次算起，所以第一次重试就是 base（默认 60 秒），之后 2、4、8、16 分钟，
     * 到 20 分钟封顶。指数也封在 2^7，避免把 {@link Duration} 乘出溢出。</p>
     */
    private Duration delayAfter(int attempts) {
        Duration base = properties.getDeletionRetryInitialDelay();
        Duration max = properties.getDeletionRetryMaxDelay();
        if (base == null || base.isNegative() || base.isZero()) {
            return Duration.ZERO;
        }
        if (max == null || max.compareTo(base) < 0) {
            max = base;
        }
        int exponent = Math.min(Math.max(attempts, 0), 7);
        Duration delay = base.multipliedBy(1L << exponent);
        return delay.compareTo(max) > 0 ? max : delay;
    }

    private static String safeError(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        String redacted = API_KEY_PATTERN.matcher(message).replaceAll("[redacted-key]");
        redacted = BEARER_PATTERN.matcher(redacted).replaceAll("$1[redacted]");
        return limit(redacted.replaceAll("\\s+", " ").strip());
    }

    private static String limit(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH
                ? value
                : value.substring(0, MAX_ERROR_LENGTH);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }
}
