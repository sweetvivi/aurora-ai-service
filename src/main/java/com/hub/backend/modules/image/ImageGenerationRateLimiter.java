package com.hub.backend.modules.image;

import com.hub.backend.common.config.properties.RateLimitProperties;
import com.hub.backend.common.exception.TooManyRequestsException;
import com.hub.backend.common.ratelimit.RateLimiter;
import com.hub.backend.common.ratelimit.RateLimitDecision;
import com.hub.backend.modules.auth.dto.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按账号限制出图张数。
 *
 * <p>图片是站内唯一「点一下就真实扣上游钱」的动作，之前一条限制都没有：一个登录账号写个脚本
 * 就能持续刷图，<code>NEW_API_ENABLED=false</code> 的部署还会直接烧平台静态 key。余额闸门只在
 * 余额归零时才拦，挡住的是「没钱」而不是「刷太快」。</p>
 *
 * <p>计数单位是<em>张</em>而不是请求：文生图一次可以要 4 张，按请求计数等于把配额放大四倍。
 * 调用方必须传入本次请求实际会生成的张数。</p>
 *
 * <p>多副本：计数后端由 {@code app.redis.enabled} 决定（见 {@link RateLimiter}）。关闭时按实例
 * 计数，实际上限是「配置值 × 副本数」；打开后全站共用一个窗口。</p>
 */
@Component
public class ImageGenerationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationRateLimiter.class);

    static final String KEY_PREFIX = "image:";

    private final RateLimiter limiter;
    private final RateLimitProperties rateLimitProperties;

    public ImageGenerationRateLimiter(RateLimiter limiter,
                                      RateLimitProperties rateLimitProperties) {
        this.limiter = limiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    /**
     * 记录本次要生成的张数，超出配额时拒绝。
     *
     * @param imageCount 本次会向上游要几张图，至少 1
     * @throws TooManyRequestsException 配额已用完，响应带 {@code Retry-After}
     */
    public void checkAndRecord(CurrentUser user, int imageCount) {
        if (!rateLimitProperties.isEnabled() || user == null) {
            return;
        }

        RateLimitProperties.Image image = rateLimitProperties.getImage();
        String key = KEY_PREFIX + user.userId();

        RateLimitDecision decision = limiter.tryAcquire(
                key, Math.max(1, imageCount), Math.max(1, image.getMaxImagesPerUser()), image.getWindow());

        if (!decision.allowed()) {
            log.warn("Image quota exhausted for user {}: {} pictures per window, retry in {}s",
                    user.userId(), image.getMaxImagesPerUser(), decision.retryAfterSeconds());
            throw new TooManyRequestsException(
                    "出图太频繁了，请 " + decision.retryAfterSeconds() + " 秒后再试",
                    decision.retryAfterSeconds());
        }
    }
}
