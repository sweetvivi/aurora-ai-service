package com.hub.backend.modules.auth;

import com.hub.backend.common.config.properties.CaptchaProperties;
import com.hub.backend.common.config.properties.RateLimitProperties;
import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.TooManyRequestsException;
import com.hub.backend.common.ratelimit.RateLimiter;
import com.hub.backend.common.ratelimit.RateLimitDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Image captcha gating account creation.
 *
 * <p>Why this exists on top of {@link RegistrationRateLimiter}: that limiter counts attempts per
 * address inside one process, so it slows a single script down but not a distributed one. A captcha
 * adds a cost that does not depend on the source address — each attempt needs a human to read an
 * image that the server never describes in text.</p>
 *
 * <p>Design constraints, in the order they shaped the code:</p>
 *
 * <ul>
 *   <li><b>Server side only.</b> The answer never leaves the process, and the image is a PNG
 *       rendered from the answer rather than text the client could strip out of an SVG.</li>
 *   <li><b>One attempt per challenge.</b> A challenge is removed on the first verification, so a
 *       wrong guess costs a fresh image. Combined with the issuing limit this caps guessing at a few
 *       dozen attempts per hour per address instead of thousands.</li>
 *   <li><b>Bounded memory.</b> Challenges live in a map capped by
 *       {@code app.captcha.max-stored}; expired entries are dropped first, then the oldest ones.
 *       There is no Redis in this deployment yet, so this is per instance — the same trade the rate
 *       limiter makes.</li>
 *   <li><b>Readable by humans.</b> The alphabet omits {@code 0/O} and {@code 1/I/L}, the answer is
 *       compared case insensitively, and the distortion stays light enough to read on a phone.</li>
 * </ul>
 */
@Component
public class CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

    /** Characters that stay unambiguous once distorted. */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private static final int WIDTH = 132;
    private static final int HEIGHT = 44;
    private static final String PNG_DATA_URL_PREFIX = "data:image/png;base64,";

    /** Key prefix of the issuing limiter, kept apart from {@code register:} in the shared limiter. */
    static final String ISSUE_KEY_PREFIX = "captcha:";

    private static final String MISSING_MESSAGE = "请先完成图形验证码";
    private static final String INVALID_MESSAGE =
            "验证码不正确或已过期，请刷新后重试";

    private final ConcurrentHashMap<String, StoredChallenge> challenges = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final CaptchaProperties properties;
    private final RateLimiter limiter;
    private final RateLimitProperties rateLimitProperties;

    public CaptchaService(
            Clock clock,
            CaptchaProperties properties,
            RateLimiter limiter,
            RateLimitProperties rateLimitProperties) {
        this.clock = clock;
        this.properties = properties;
        this.limiter = limiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    /** Whether {@code POST /auth/register} currently demands a captcha. */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * Issues one challenge.
     *
     * <p>The answer is part of the returned value because it is the only place it exists in
     * readable form; the controller maps the response without it. Nothing logs it.</p>
     *
     * @param clientIp address used for the issuing limit; may be {@code null} in tests
     * @throws TooManyRequestsException when the address asked for too many images
     */
    public Challenge create(String clientIp) {
        checkIssueQuota(clientIp);

        String code = randomCode(properties.getLength());
        String id = randomId();
        long expiresAt = clock.millis() + properties.getTtl().toMillis();

        store(id, new StoredChallenge(code, expiresAt));
        return new Challenge(id, code, PNG_DATA_URL_PREFIX + renderBase64(code), properties.getTtl());
    }

    /**
     * Consumes the challenge and checks the answer.
     *
     * <p>The challenge is removed whatever the outcome: a wrong answer must not be retryable against
     * the same image, otherwise one issued image would allow unlimited guessing.</p>
     *
     * @throws InvalidRequestException when the captcha is missing, unknown, expired or wrong
     */
    public void requireValid(String captchaId, String captchaCode) {
        if (!properties.isEnabled()) {
            return;
        }
        if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            throw new InvalidRequestException(MISSING_MESSAGE);
        }

        StoredChallenge stored = challenges.remove(captchaId.strip());
        if (stored == null || stored.expiresAt() <= clock.millis()) {
            throw new InvalidRequestException(INVALID_MESSAGE);
        }

        String answer = captchaCode.strip().toUpperCase(Locale.ROOT);
        if (!stored.code().equals(answer)) {
            throw new InvalidRequestException(INVALID_MESSAGE);
        }
    }

    /** @return number of live challenges, for diagnostics */
    public int storedChallenges() {
        return challenges.size();
    }

    private void checkIssueQuota(String clientIp) {
        /*
         * The global switch wins over the captcha's own one. Both are documented as "turn it off for
         * tests and local debugging", so leaving this limiter out made the switch a half truth: with
         * app.rate-limit.enabled=false every other limiter went quiet while GET /auth/captcha kept
         * answering 429, which looks like a bug in whatever the caller was actually testing.
         */
        if (!rateLimitProperties.isEnabled()) {
            return;
        }

        String key = ISSUE_KEY_PREFIX + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
        RateLimitDecision decision = limiter.tryAcquire(
                key, properties.getMaxIssuesPerIp(), properties.getIssueWindow());
        if (!decision.allowed()) {
            log.warn("Captcha issuing limit hit for {}; retry in {}s", key, decision.retryAfterSeconds());
            throw new TooManyRequestsException(
                    ApiMessages.CODE_RATE_LIMITED,
                    decision.retryAfterSeconds());
        }
    }

    private void store(String id, StoredChallenge challenge) {
        evictIfOverCapacity();
        challenges.put(id, challenge);
    }

    /**
     * Keeps the map bounded. Expired challenges go first because they can never be answered again;
     * if the flood is newer than the TTL, the oldest are dropped instead of letting memory grow.
     */
    private void evictIfOverCapacity() {
        int max = Math.max(1, properties.getMaxStored());
        if (challenges.size() < max) {
            return;
        }

        long now = clock.millis();
        challenges.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        if (challenges.size() < max) {
            return;
        }

        int excess = challenges.size() - max + 1;
        challenges.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().expiresAt()))
                .limit(Math.max(1, excess))
                .map(java.util.Map.Entry::getKey)
                .toList()
                .forEach(challenges::remove);
    }

    private String randomCode(int length) {
        int size = Math.max(1, length);
        StringBuilder code = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }

    /** 128 bits from {@link SecureRandom}, hex encoded: guessing an id is harder than guessing a code. */
    private String randomId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder id = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            id.append(Character.forDigit((value >> 4) & 0xF, 16));
            id.append(Character.forDigit(value & 0xF, 16));
        }
        return id.toString();
    }

    /**
     * Draws the answer into a PNG.
     *
     * <p>Rendered with Java2D so no binary asset and no external service is involved. The noise is
     * tuned to defeat naive OCR without making the image hard to read: faint lines, per character
     * rotation and colour, and dots that never cover a glyph entirely.</p>
     */
    private String renderBase64(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            graphics.setColor(new Color(244, 246, 250));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);

            graphics.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < 6; i++) {
                graphics.setColor(new Color(
                        170 + random.nextInt(50), 180 + random.nextInt(50), 200 + random.nextInt(50)));
                graphics.drawLine(
                        random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }

            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            int step = WIDTH / (code.length() + 1);
            for (int i = 0; i < code.length(); i++) {
                double angle = Math.toRadians(random.nextInt(36) - 18);
                int x = step * (i + 1) - 6;
                int y = HEIGHT / 2 + 10 + random.nextInt(6) - 3;
                graphics.setColor(new Color(
                        20 + random.nextInt(80), 40 + random.nextInt(80), 110 + random.nextInt(90)));

                AffineTransform original = graphics.getTransform();
                graphics.rotate(angle, x, y);
                graphics.drawString(String.valueOf(code.charAt(i)), x, y);
                graphics.setTransform(original);
            }

            for (int i = 0; i < 30; i++) {
                graphics.setColor(new Color(
                        120 + random.nextInt(100), 130 + random.nextInt(90), 170 + random.nextInt(80)));
                graphics.fillOval(random.nextInt(WIDTH), random.nextInt(HEIGHT), 2, 2);
            }
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException | RuntimeException failure) {
            // A headless image without any usable font renders nothing, which would leave users
            // reading a blank captcha. Failing loudly here beats issuing an unanswerable challenge.
            log.error("Captcha image rendering failed", failure);
            throw new UncheckedIOException("Captcha image could not be rendered", new IOException(failure));
        }
    }

    /**
     * One issued challenge.
     *
     * @param id        opaque handle the client sends back; safe to expose
     * @param code      the answer, never serialised to the client
     * @param image     PNG data URL ready to use as an {@code img} source
     * @param ttl       how long the challenge stays answerable
     */
    public record Challenge(String id, String code, String image, Duration ttl) {
    }

    private record StoredChallenge(String code, long expiresAt) {
    }
}
