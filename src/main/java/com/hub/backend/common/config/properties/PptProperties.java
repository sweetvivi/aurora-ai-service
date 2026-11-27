package com.hub.backend.common.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * PPT generation settings (Claude Agent + optional legacy worker + estimate).
 */
@ConfigurationProperties(prefix = "app.ppt")
public class PptProperties {

    private final PptMaster pptMaster = new PptMaster();
    private final Estimate estimate = new Estimate();

    public PptMaster getPptMaster() {
        return pptMaster;
    }

    public Estimate getEstimate() {
        return estimate;
    }

    public static class PptMaster {
        /**
         * {@code agent} = Claude Code CLI + ppt-master Skill (product default);
         * {@code process} = spawn {@code worker/generate.py};
         * {@code http} = POST to {@link #baseUrl}/v1/generate.
         */
        private String mode = "agent";
        /** HTTP worker base; must be set via {@code PPT_MASTER_BASE_URL} when mode is http. */
        private String baseUrl = "";
        /** Local ppt-master checkout; must be set via {@code PPT_MASTER_REPO}. */
        private String repoRoot = "";
        private String python = "python";
        private String workDir = "./data/ppt-master-jobs";
        private String model = "";
        private Duration generateTimeout = Duration.ofMinutes(45);
        /** 无文本 Key 时禁止静默离线薄文案；仅调试可显式打开。 */
        private boolean allowOffline = false;
        /** Concurrent image generations inside the worker when autoImg is on. */
        private int imageConcurrency = 2;
        /** Concurrent whole-job Agent/worker runs. */
        private int jobConcurrency = 2;
        /** Waiting buffer before create returns 429. */
        private int jobQueueSize = 32;
        /** Claude Code CLI binary. */
        private String claudeBin = "claude";
        private String agentModel = "sonnet";
        private String agentBaseUrl = "";
        private String agentAuthToken = "";
        /** Official Deck root; empty → {repo}/skills/ppt-master/templates/decks */
        private String deckTemplatesRoot = "";
        private Duration beautyStageTimeout = Duration.ofMinutes(8);

        /**
         * 恢复扫描开关。
         *
         * <p>关掉它，任务就重新退化成「只在当前进程的内存里排队」：重启即卡死。默认打开。</p>
         */
        private boolean recoveryEnabled = true;
        /** 两轮扫描之间的间隔。只做两次轻量查询，被拒绝时也很快返回。 */
        private Duration recoveryInterval = Duration.ofSeconds(30);
        /** 启动后多久开始第一轮：留出连接池与 Flyway 就绪的时间，又不能让重启后的任务干等。 */
        private Duration recoveryInitialDelay = Duration.ofSeconds(15);
        /** 单轮最多重新提交多少个任务，避免一次重启把所有任务同时压给线程池。 */
        private int recoveryBatchSize = 20;
        /** queued 超过这么久还没被领取，视为提交丢失。要大于正常排队耗时，否则会把排队误判成丢失。 */
        private Duration queuedStaleAfter = Duration.ofMinutes(2);
        /**
         * queued 的最长存活时间。
         *
         * <p>修复重启丢任务之后，线上那批「卡了很久」的历史行会第一次被扫描到。超过这个时长的不再
         * 复活，直接判失败让用户重新发起 —— 把几天前的任务悄悄跑起来，用户既没在等，也可能不认这笔消耗。</p>
         */
        private Duration queuedMaxAge = Duration.ofHours(24);
        /**
         * 租约时长：领取时写入，每次推进进度续期。
         *
         * <p><b>必须大于 {@link #generateTimeout}</b>，否则一次正常但缓慢的上游调用期间没有进度写入，
         * 会被判成「执行者已退出」而误杀。默认 90 分钟相对 45 分钟的超时留了一倍余量。</p>
         */
        private Duration leaseTtl = Duration.ofMinutes(90);

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode == null || mode.isBlank() ? "agent" : mode.trim();
        }

        public boolean useAgent() {
            String m = mode == null ? "" : mode.trim().toLowerCase();
            return m.isEmpty() || "agent".equals(m) || "claude".equals(m) || "claude-agent".equals(m);
        }

        public boolean useHttp() {
            return "http".equalsIgnoreCase(mode);
        }

        public boolean useProcess() {
            return "process".equalsIgnoreCase(mode);
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getRepoRoot() {
            return repoRoot;
        }

        public void setRepoRoot(String repoRoot) {
            this.repoRoot = repoRoot;
        }

        public String getPython() {
            return python;
        }

        public void setPython(String python) {
            this.python = python;
        }

        public String getWorkDir() {
            return workDir;
        }

        public void setWorkDir(String workDir) {
            this.workDir = workDir;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Duration getGenerateTimeout() {
            return generateTimeout;
        }

        public void setGenerateTimeout(Duration generateTimeout) {
            this.generateTimeout = generateTimeout;
        }

        public boolean isAllowOffline() {
            return allowOffline;
        }

        public void setAllowOffline(boolean allowOffline) {
            this.allowOffline = allowOffline;
        }

        public int getImageConcurrency() {
            return imageConcurrency;
        }

        public void setImageConcurrency(int imageConcurrency) {
            this.imageConcurrency = Math.max(1, imageConcurrency);
        }

        public int getJobConcurrency() {
            return jobConcurrency;
        }

        public void setJobConcurrency(int jobConcurrency) {
            this.jobConcurrency = Math.max(1, jobConcurrency);
        }

        public int getJobQueueSize() {
            return jobQueueSize;
        }

        public void setJobQueueSize(int jobQueueSize) {
            this.jobQueueSize = Math.max(1, jobQueueSize);
        }

        public String getClaudeBin() {
            return claudeBin;
        }

        public void setClaudeBin(String claudeBin) {
            this.claudeBin = claudeBin == null || claudeBin.isBlank() ? "claude" : claudeBin.trim();
        }

        public String getAgentModel() {
            return agentModel;
        }

        public void setAgentModel(String agentModel) {
            this.agentModel = agentModel;
        }

        public String getAgentBaseUrl() {
            return agentBaseUrl;
        }

        public void setAgentBaseUrl(String agentBaseUrl) {
            this.agentBaseUrl = agentBaseUrl;
        }

        public String getAgentAuthToken() {
            return agentAuthToken;
        }

        public void setAgentAuthToken(String agentAuthToken) {
            this.agentAuthToken = agentAuthToken;
        }

        public String getDeckTemplatesRoot() {
            return deckTemplatesRoot;
        }

        public void setDeckTemplatesRoot(String deckTemplatesRoot) {
            this.deckTemplatesRoot = deckTemplatesRoot;
        }

        public Duration getBeautyStageTimeout() {
            return beautyStageTimeout;
        }

        public void setBeautyStageTimeout(Duration beautyStageTimeout) {
            this.beautyStageTimeout = beautyStageTimeout;
        }

        public boolean isRecoveryEnabled() {
            return recoveryEnabled;
        }

        public void setRecoveryEnabled(boolean recoveryEnabled) {
            this.recoveryEnabled = recoveryEnabled;
        }

        public Duration getRecoveryInterval() {
            return recoveryInterval;
        }

        public void setRecoveryInterval(Duration recoveryInterval) {
            this.recoveryInterval = recoveryInterval;
        }

        public Duration getRecoveryInitialDelay() {
            return recoveryInitialDelay;
        }

        public void setRecoveryInitialDelay(Duration recoveryInitialDelay) {
            this.recoveryInitialDelay = recoveryInitialDelay;
        }

        public int getRecoveryBatchSize() {
            return recoveryBatchSize;
        }

        public void setRecoveryBatchSize(int recoveryBatchSize) {
            this.recoveryBatchSize = recoveryBatchSize;
        }

        public Duration getQueuedStaleAfter() {
            return queuedStaleAfter;
        }

        public void setQueuedStaleAfter(Duration queuedStaleAfter) {
            this.queuedStaleAfter = queuedStaleAfter;
        }

        public Duration getQueuedMaxAge() {
            return queuedMaxAge;
        }

        public void setQueuedMaxAge(Duration queuedMaxAge) {
            this.queuedMaxAge = queuedMaxAge;
        }

        public Duration getLeaseTtl() {
            return leaseTtl;
        }

        public void setLeaseTtl(Duration leaseTtl) {
            this.leaseTtl = leaseTtl;
        }
    }

    public static class Estimate {
        private int basePoints = 8;
        private int perSlidePoints = 0;
        /** Points charged per estimated auto-illustration (not a flat fee). */
        private int autoImgPoints = 2;
        private int webSearchPoints = 1;

        public int getBasePoints() {
            return basePoints;
        }

        public void setBasePoints(int basePoints) {
            this.basePoints = basePoints;
        }

        public int getPerSlidePoints() {
            return perSlidePoints;
        }

        public void setPerSlidePoints(int perSlidePoints) {
            this.perSlidePoints = perSlidePoints;
        }

        public int getAutoImgPoints() {
            return autoImgPoints;
        }

        public void setAutoImgPoints(int autoImgPoints) {
            this.autoImgPoints = autoImgPoints;
        }

        public int getWebSearchPoints() {
            return webSearchPoints;
        }

        public void setWebSearchPoints(int webSearchPoints) {
            this.webSearchPoints = webSearchPoints;
        }
    }
}
