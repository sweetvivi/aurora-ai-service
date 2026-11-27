package com.hub.backend.modules.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.AuthProperties;
import com.hub.backend.common.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenServiceTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hs256";
    private static final long USER_ID = 42L;
    private static final long SESSION_ID = 7L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AuthProperties authProperties;
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties();
        authProperties.setJwtSecret(SECRET);
        tokenService = new TokenService(authProperties, objectMapper);
    }

    @Test
    void issuesATokenThatVerifies() {
        TokenService.IssuedToken issued = tokenService.issueAccessToken(USER_ID, SESSION_ID);

        TokenService.AccessTokenClaims claims = tokenService.parseAccessToken(issued.token());

        assertEquals(USER_ID, claims.userId().longValue());
        assertEquals(SESSION_ID, claims.sessionId().longValue());
        assertEquals(issued.expiresAt().getEpochSecond(), claims.expiresAt().getEpochSecond());
        assertEquals(3, issued.token().split("\\.").length);
    }

    @Test
    void rejectsATamperedPayload() {
        String token = tokenService.issueAccessToken(USER_ID, SESSION_ID).token();
        String[] parts = token.split("\\.");
        String forged = parts[0] + "."
                + base64Url("{\"iss\":\"backend-service\",\"sub\":\"999\",\"sid\":7,\"exp\":9999999999}")
                + "." + parts[2];

        UnauthorizedException exception = assertThrows(UnauthorizedException.class,
                () -> tokenService.parseAccessToken(forged));

        assertEquals("Access token signature is invalid", exception.getMessage());
    }

    @Test
    void rejectsATamperedSignature() {
        String token = tokenService.issueAccessToken(USER_ID, SESSION_ID).token();
        String[] parts = token.split("\\.");

        assertThrows(UnauthorizedException.class,
                () -> tokenService.parseAccessToken(parts[0] + "." + parts[1] + "." + base64Url("nonsense")));
    }

    @Test
    void rejectsAnExpiredToken() {
        authProperties.setAccessTokenTtl(Duration.ofSeconds(-30));
        TokenService expiring = new TokenService(authProperties, objectMapper);

        String token = expiring.issueAccessToken(USER_ID, SESSION_ID).token();

        UnauthorizedException exception = assertThrows(UnauthorizedException.class,
                () -> expiring.parseAccessToken(token));
        assertEquals("Access token has expired", exception.getMessage());
    }

    @Test
    void rejectsAnUnsignedTokenEvenWhenTheAlgorithmLooksAcceptable() {
        String payload = base64Url("{\"iss\":\"backend-service\",\"sub\":\"42\",\"sid\":7,\"exp\":9999999999}");
        String unsigned = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + payload + ".";

        assertThrows(UnauthorizedException.class, () -> tokenService.parseAccessToken(unsigned));
    }

    @Test
    void rejectsAWellSignedTokenThatDeclaresAnotherAlgorithm() {
        // Pins the algorithm: even a token whose signature matches must be refused when its header
        // claims something other than HS256, which is how alg confusion attacks start.
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"iss\":\"backend-service\",\"sub\":\"42\",\"sid\":7,\"exp\":9999999999}");
        String forged = header + "." + payload + "." + base64Url(hmac(header + "." + payload, SECRET));

        UnauthorizedException exception = assertThrows(UnauthorizedException.class,
                () -> tokenService.parseAccessToken(forged));

        assertEquals("Access token uses an unsupported algorithm", exception.getMessage());
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        AuthProperties other = new AuthProperties();
        other.setJwtSecret("a-completely-different-secret-value-32b");
        TokenService otherService = new TokenService(other, objectMapper);

        String token = otherService.issueAccessToken(USER_ID, SESSION_ID).token();

        assertThrows(UnauthorizedException.class, () -> tokenService.parseAccessToken(token));
    }

    @Test
    void rejectsATokenIssuedByAnotherService() {
        AuthProperties other = new AuthProperties();
        other.setJwtSecret(SECRET);
        other.setIssuer("some-other-service");
        TokenService otherService = new TokenService(other, objectMapper);

        String token = otherService.issueAccessToken(USER_ID, SESSION_ID).token();

        UnauthorizedException exception = assertThrows(UnauthorizedException.class,
                () -> tokenService.parseAccessToken(token));
        assertEquals("Access token was issued by another service", exception.getMessage());
    }

    @Test
    void rejectsGarbageAndEmptyTokens() {
        assertThrows(UnauthorizedException.class, () -> tokenService.parseAccessToken("not-a-token"));
        assertThrows(UnauthorizedException.class, () -> tokenService.parseAccessToken("a.b"));
        assertThrows(UnauthorizedException.class, () -> tokenService.parseAccessToken("  "));
        assertThrows(UnauthorizedException.class, () -> tokenService.parseAccessToken(null));
    }

    @Test
    void refusesToStartWithASecretThatIsTooShort() {
        AuthProperties weak = new AuthProperties();
        weak.setJwtSecret("too-short");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new TokenService(weak, objectMapper));

        assertTrue(exception.getMessage().contains("at least"));
    }

    @Test
    void refusesToStartWithoutAConfiguredSecret() {
        AuthProperties missing = new AuthProperties();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new TokenService(missing, objectMapper));

        assertEquals(
                "AUTH_JWT_SECRET must be configured; the service has no default signing key",
                exception.getMessage());
    }

    @Test
    void refreshTokensAreUniqueAndUrlSafe() {
        String first = tokenService.newRefreshToken();
        String second = tokenService.newRefreshToken();

        assertNotEquals(first, second);
        assertTrue(first.length() >= 40, first);
        assertTrue(first.matches("[A-Za-z0-9_-]+"), first);
    }

    @Test
    void hashesTokensToLowercaseSha256Hex() {
        String hash = tokenService.hashToken("some-token");

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"), hash);
        assertEquals(hash, tokenService.hashToken("some-token"));
        assertNotEquals(hash, tokenService.hashToken("some-other-token"));
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] hmac(String signingInput, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
