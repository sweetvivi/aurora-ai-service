package com.hub.backend.modules.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 一条「远端 New API 账号还没删掉」的待办。
 *
 * <p>字段与 {@code V103__newapi_account_deletion_tasks.sql} 一一对应：MySQL profile 下
 * {@code ddl-auto} 是 {@code validate}，改实体必须同步改迁移脚本，否则应用直接起不来。</p>
 *
 * <p>存在的理由只有一个：New API 的 {@code /api/verify} 和一批关键接口有 20 次 / 20 分钟的
 * 限流，批量注销时 429 是常态。撞上之后不能就这么算了 —— 本地账号已经删除，远端行要是留着，
 * 余额和用户名就一起留在上游，而且那个用户名再也注册不了。所以失败的那一次落成一行，
 * 由 {@code NewApiAccountDeletionWorker} 在限流窗口过去之后接着删。</p>
 *
 * <p>不建指向 {@code user_accounts} 的外键，也不存用户快照：本地行入队之后就被物理删除了，
 * 这条任务必须比它活得久，所以 {@code local_user_id} 只是一个用于追溯的数字。</p>
 */
@Entity
@Table(name = "newapi_account_deletion_tasks")
public class NewApiDeletionTaskEntity {

    /** 管理员接口物理删行（{@code DELETE /api/user/{id}}）。需要 {@code newapiUserId}。 */
    public static final String MODE_ADMIN = "ADMIN";

    /** 用户自助注销（{@code DELETE /api/user/self}）。需要用户名与密码，只能打软删标记。 */
    public static final String MODE_SELF = "SELF";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    /** 重试次数用尽仍在失败。保留整行，交给人看。 */
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "local_user_id", nullable = false)
    private Long localUserId;

    /** New API 用户 id；自助路径入队时可能还不知道。 */
    @Column(name = "newapi_user_id")
    private Long newapiUserId;

    @Column(name = "username", length = 128)
    private String username;

    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    /**
     * 自助路径的密码密文。
     *
     * <p>New API 的自助注销要密码做 step-up 验证，而本地会话里的明文副本会在注销结束时被
     * 清掉（{@code SessionPasswordVault.forget}）。要在这之后还能重放，只能自己存一份 ——
     * 用与 PAT 相同的 AES-GCM 密钥加密，任务成功后整行删除，不长期保留。</p>
     */
    @Lob
    @Column(name = "password_ciphertext")
    private byte[] passwordCiphertext;

    @Column(name = "password_nonce", length = 12)
    private byte[] passwordNonce;

    @Column(name = "password_tag", length = 16)
    private byte[] passwordTag;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected NewApiDeletionTaskEntity() {
    }

    public NewApiDeletionTaskEntity(Long localUserId,
                                    Long newapiUserId,
                                    String username,
                                    String mode,
                                    LocalDateTime nextAttemptAt) {
        this.localUserId = localUserId;
        this.newapiUserId = newapiUserId;
        this.username = username;
        this.mode = mode;
        this.status = STATUS_PENDING;
        this.nextAttemptAt = nextAttemptAt;
    }

    /** 入队时那一次失败的原因；之后重试再失败会被覆盖。 */
    public void noteInitialFailure(String error) {
        this.lastError = error;
    }

    public void sealPassword(byte[] ciphertext, byte[] nonce, byte[] tag) {
        this.passwordCiphertext = ciphertext;
        this.passwordNonce = nonce;
        this.passwordTag = tag;
    }

    public boolean hasSealedPassword() {
        return passwordCiphertext != null && passwordCiphertext.length > 0
                && passwordNonce != null && passwordTag != null;
    }

    /**
     * 这次没删成：记一次失败，排下一次时间。
     *
     * <p>{@code attempts} 只在这里加，所以「重试了几次」与「还剩几次」是同一个数，
     * 上限判断不会因为实例重启而重置。</p>
     */
    public void reschedule(LocalDateTime nextAttemptAt, String error) {
        this.attempts++;
        this.status = STATUS_PENDING;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = error;
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    /** 次数用尽。行留着，人还能看见是哪个账号没删干净、卡在什么错上。 */
    public void giveUp(String error) {
        this.attempts++;
        this.status = STATUS_FAILED;
        this.lastError = error;
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    public Long getId() {
        return id;
    }

    public Long getLocalUserId() {
        return localUserId;
    }

    public Long getNewapiUserId() {
        return newapiUserId;
    }

    public String getUsername() {
        return username;
    }

    public String getMode() {
        return mode;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public byte[] getPasswordCiphertext() {
        return passwordCiphertext;
    }

    public byte[] getPasswordNonce() {
        return passwordNonce;
    }

    public byte[] getPasswordTag() {
        return passwordTag;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public LocalDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
