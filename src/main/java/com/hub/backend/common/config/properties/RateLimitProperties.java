package com.hub.backend.common.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Request rate limits.
 *
 * <p>Bound from {@code app.rate-limit.*}.</p>
 *
 * <p>Only registration is limited for now. Registration is the cheapest endpoint to abuse — it
 * creates rows and burns CPU on password hashing — so it is the first one guarded. Login, upload
 * and chat throttling belong to the Redis backed stage described in
 * {@code docs/ai1foo-rebuild-plan.md} §9.</p>
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    /** Set to false to disable every limit; intended for tests and local debugging. */
    private boolean enabled = true;

    /** Maximum number of keys tracked before stale entries are evicted. */
    private int maxTrackedKeys = 100_000;

    /**
     * Redis 实现的键前缀（内存实现不用）。
     *
     * <p>同一个 Redis 实例可能被多个服务共用，加前缀是为了 {@code clear()}（按前缀 SCAN + DEL）
     * 不会误删别人的键。换服务时改这一处即可。</p>
     */
    private String keyPrefix = "rl:";

    private Registration registration = new Registration();

    private Login login = new Login();

    private EmailCode emailCode = new EmailCode();

    private Image image = new Image();

    /**
     * Per address and per mailbox limit on {@code POST /api/v1/auth/email-code}.
     *
     * <p>Two keys, because one is not enough: the address limit alone lets a script walk a list of
     * mailboxes from one host, and the mailbox limit alone lets a botnet mail-bomb one person. The
     * mailbox count is deliberately lower — a person asks for a second code, not a fifth.</p>
     */
    public static class EmailCode {

        /** Codes requested per source address per window. */
        private int maxPerIp = 10;

        /** Codes requested for the same mailbox per window. */
        private int maxPerEmail = 3;

        /** Length of the counting window. */
        private Duration window = Duration.ofMinutes(10);

        public int getMaxPerIp() {
            return maxPerIp;
        }

        public void setMaxPerIp(int maxPerIp) {
            this.maxPerIp = maxPerIp;
        }

        public int getMaxPerEmail() {
            return maxPerEmail;
        }

        public void setMaxPerEmail(int maxPerEmail) {
            this.maxPerEmail = maxPerEmail;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }

    public EmailCode getEmailCode() {
        return emailCode;
    }

    public void setEmailCode(EmailCode emailCode) {
        this.emailCode = emailCode;
    }

    /**
     * Per account limit on image generation.
     *
     * <p>Counted in <em>pictures</em>, not requests: one request may ask for four, and picture
     * generation is the expensive, upstream-billed action — a per request limit would let a script
     * spend four times the intended quota without ever tripping.</p>
     */
    public static class Image {

        /**
         * Pictures accepted per account per window. Defaults to 24, which is six full four-picture
         * batches a minute — well above what a person clicks, still far below what a script wants.
         */
        private int maxImagesPerUser = 24;

        /** Length of the counting window. */
        private Duration window = Duration.ofMinutes(1);

        public int getMaxImagesPerUser() {
            return maxImagesPerUser;
        }

        public void setMaxImagesPerUser(int maxImagesPerUser) {
            this.maxImagesPerUser = maxImagesPerUser;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }

    /**
     * 登录失败限流：{@code POST /api/v1/auth/login}。
     *
     * <p>只数失败：登录成功不消耗额度，密码错误才记一笔。按 IP 与登录名各一把账 —— 只按 IP，
     * 换一个出口就能继续撞；只按登录名，一个人被撞锁后攻击者可以拿它当拒绝服务开关。</p>
     *
     * <p>数值刻意留出正常人的错手空间：同一登录名 15 分钟内 5 次密码错误才锁，锁的是这个
     * 登录名与这个来源，其它账号照常登录。</p>
     */
    public static class Login {

        /** 同一来源 IP 在窗口内允许的登录失败次数。 */
        private int maxFailuresPerIp = 20;

        /** 同一登录名在窗口内允许的登录失败次数。 */
        private int maxFailuresPerUsername = 5;

        /** 计数窗口长度。 */
        private Duration window = Duration.ofMinutes(15);

        public int getMaxFailuresPerIp() {
            return maxFailuresPerIp;
        }

        public void setMaxFailuresPerIp(int maxFailuresPerIp) {
            this.maxFailuresPerIp = maxFailuresPerIp;
        }

        public int getMaxFailuresPerUsername() {
            return maxFailuresPerUsername;
        }

        public void setMaxFailuresPerUsername(int maxFailuresPerUsername) {
            this.maxFailuresPerUsername = maxFailuresPerUsername;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }

    /**
     * Per address limit on {@code POST /api/v1/auth/register}.
     */
    public static class Registration {

        /**
         * Attempts accepted per window. One per 15 seconds: the window is a short cool down rather
         * than a daily quota, so a rejected caller never waits longer than 15 seconds. That holds
         * even behind a reverse proxy, where every request shares one key.
         */
        private int maxAttemptsPerIp = 1;

        /** Length of the counting window. */
        private Duration window = Duration.ofSeconds(15);

        public int getMaxAttemptsPerIp() {
            return maxAttemptsPerIp;
        }

        public void setMaxAttemptsPerIp(int maxAttemptsPerIp) {
            this.maxAttemptsPerIp = maxAttemptsPerIp;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxTrackedKeys() {
        return maxTrackedKeys;
    }

    public void setMaxTrackedKeys(int maxTrackedKeys) {
        this.maxTrackedKeys = maxTrackedKeys;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix.trim();
    }

    public Login getLogin() {
        return login;
    }

    public void setLogin(Login login) {
        this.login = login;
    }

    public Registration getRegistration() {
        return registration;
    }

    public void setRegistration(Registration registration) {
        this.registration = registration;
    }

    public Image getImage() {
        return image;
    }

    public void setImage(Image image) {
        this.image = image;
    }
}
