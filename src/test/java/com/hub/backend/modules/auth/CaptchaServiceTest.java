package com.hub.backend.modules.auth;

import com.hub.backend.common.config.properties.CaptchaProperties;
import com.hub.backend.common.config.properties.RateLimitProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.TooManyRequestsException;
import com.hub.backend.common.ratelimit.InMemoryRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the registration captcha, without Spring.
 *
 * <p>The cases that matter are the ones an attacker would reach for: guessing twice against one
 * image, replaying a code, and answering an expired challenge. Each of them must be rejected by the
 * service rather than by the controller, because the controller only forwards strings.</p>
 */
class CaptchaServiceTest {

    private static final String IP = "203.0.113.9";
    private static final String DATA_URL_PREFIX = "data:image/png;base64,";

    private MutableClock clock;
    private CaptchaProperties properties;
    private RateLimitProperties rateLimitProperties;
    private CaptchaService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        properties = new CaptchaProperties();
        rateLimitProperties = new RateLimitProperties();
        service = new CaptchaService(
                clock,
                properties,
                new InMemoryRateLimiter(clock, rateLimitProperties),
                rateLimitProperties);
    }

    @Test
    void issuesAReadablePngAndAnAnswerOfTheConfiguredLength() throws Exception {
        CaptchaService.Challenge challenge = service.create(IP);

        assertNotNull(challenge.id());
        assertEquals(16 * 2, challenge.id().length(), "id should be 128 random bits in hex");
        assertEquals(properties.getLength(), challenge.code().length());
        assertEquals(properties.getTtl(), challenge.ttl());
        assertTrue(challenge.image().startsWith(DATA_URL_PREFIX));

        byte[] png = Base64.getDecoder().decode(challenge.image().substring(DATA_URL_PREFIX.length()));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(decoded, "the data URL must decode to a real PNG");
        assertTrue(decoded.getWidth() > 0 && decoded.getHeight() > 0);
        // Not a blank canvas: a rendered captcha must differ from a solid background.
        assertTrue(png.length > 500, "a rendered captcha should not compress to a near empty image");
    }

    @Test
    void acceptsTheCodeRegardlessOfCaseAndPadding() {
        CaptchaService.Challenge challenge = service.create(IP);

        service.requireValid(challenge.id(), "  " + challenge.code().toLowerCase(java.util.Locale.ROOT) + " ");
    }

    @Test
    void aWrongAnswerBurnsTheChallengeSoGuessingCannotContinue() {
        CaptchaService.Challenge challenge = service.create(IP);
        String wrong = challenge.code().equals("AAAA") ? "BBBB" : "AAAA";

        assertThrows(InvalidRequestException.class, () -> service.requireValid(challenge.id(), wrong));
        // The right answer no longer helps: the challenge was consumed by the failed attempt.
        assertThrows(InvalidRequestException.class, () -> service.requireValid(challenge.id(), challenge.code()));
    }

    @Test
    void aChallengeCannotBeAnsweredTwice() {
        CaptchaService.Challenge challenge = service.create(IP);
        service.requireValid(challenge.id(), challenge.code());

        assertThrows(InvalidRequestException.class, () -> service.requireValid(challenge.id(), challenge.code()));
    }

    @Test
    void anExpiredChallengeIsRejected() {
        CaptchaService.Challenge challenge = service.create(IP);
        clock.advance(properties.getTtl().plus(Duration.ofSeconds(1)));

        InvalidRequestException thrown = assertThrows(
                InvalidRequestException.class, () -> service.requireValid(challenge.id(), challenge.code()));
        // 文案是给用户看的，中文原话会被 GlobalExceptionHandler 原样保留；这里锁「过期」二字。
        assertTrue(thrown.getMessage().contains("已过期"));
    }

    @Test
    void anUnknownIdIsRejected() {
        assertThrows(InvalidRequestException.class, () -> service.requireValid("nothing-here", "ABCD"));
    }

    @Test
    void aMissingIdOrAnswerIsReportedAsRequired() {
        CaptchaService.Challenge challenge = service.create(IP);

        assertThrows(InvalidRequestException.class, () -> service.requireValid(null, "ABCD"));
        assertThrows(InvalidRequestException.class, () -> service.requireValid(challenge.id(), "  "));
    }

    @Test
    void doesNothingWhenCaptchaIsDisabled() {
        properties.setEnabled(false);
        service.requireValid("whatever", "wrong");
        assertFalse(service.isEnabled());
    }

    @Test
    void capsHowManyImagesOneAddressMayRequest() {
        properties.setMaxIssuesPerIp(2);
        properties.setIssueWindow(Duration.ofHours(1));

        service.create(IP);
        service.create(IP);

        assertThrows(TooManyRequestsException.class, () -> service.create(IP));
        // Another address keeps its own quota.
        service.create("198.51.100.4");
    }

    @Test
    void issuingResetsWithTheWindow() {
        properties.setMaxIssuesPerIp(1);
        properties.setIssueWindow(Duration.ofHours(1));

        service.create(IP);
        assertThrows(TooManyRequestsException.class, () -> service.create(IP));

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        service.create(IP);
    }

    @Test
    void theGlobalRateLimitSwitchAlsoSilencesTheIssuingLimit() {
        properties.setMaxIssuesPerIp(1);
        properties.setIssueWindow(Duration.ofHours(1));
        service.create(IP);
        assertThrows(TooManyRequestsException.class, () -> service.create(IP));

        // app.rate-limit.enabled=false is documented as "disable every limit". Before this was
        // wired up, every other limiter went quiet while this one kept answering 429 — the switch
        // looked broken for anyone debugging registration locally.
        rateLimitProperties.setEnabled(false);

        // Well inside the same window: only the switch can be letting it through.
        service.create(IP);
    }

    @Test
    void turningTheSwitchBackOnRestoresTheIssuingLimit() {
        properties.setMaxIssuesPerIp(1);
        properties.setIssueWindow(Duration.ofHours(1));
        rateLimitProperties.setEnabled(false);

        // Nothing is counted while the switch is off, so these leave no trace in the window.
        service.create(IP);
        service.create(IP);

        rateLimitProperties.setEnabled(true);

        // The window starts fresh on the first covered attempt, then applies as usual.
        service.create(IP);
        assertThrows(TooManyRequestsException.class, () -> service.create(IP));
    }

    @Test
    void dropsExpiredChallengesInsteadOfGrowingWithoutLimit() {
        properties.setMaxStored(2);

        CaptchaService.Challenge first = service.create(IP);
        clock.advance(properties.getTtl().plusSeconds(1));
        service.create(IP);
        service.create(IP);

        // The map never exceeds the cap; the expired entry is the one that went.
        assertTrue(service.storedChallenges() <= 2);
        assertThrows(InvalidRequestException.class, () -> service.requireValid(first.id(), first.code()));
    }

    /** Clock the test can move, so TTL behaviour is checked without sleeping. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
