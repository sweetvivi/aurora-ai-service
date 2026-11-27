package com.hub.backend.modules.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.AuthProperties;
import com.hub.backend.common.exception.UnauthorizedException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Issues and verifies authentication tokens.
 *
 * <p>Two token shapes, deliberately different:</p>
 * <ul>
 *   <li><strong>access token</strong> — a compact HS256 JWT, short lived, verified without a
 *       database round trip. Only the signature and {@code exp} are trusted, and the session it
 *       names is still checked against {@code auth_sessions} so that logout takes effect
 *       immediately rather than at expiry;</li>
 *   <li><strong>refresh token</strong> — an opaque random secret. Only its SHA-256 is stored, so a
 *       database leak cannot be replayed against the API.</li>
 * </ul>
 *
 * <p>The verifier pins the algorithm to HS256 and reads {@code alg} from the token <em>after</em>
 * the signature has been checked, which closes the classic {@code alg: none} / RS256 confusion
 * attacks. Signatures are compared with {@link MessageDigest#isEqual} so comparison time does not
 * depend on how many leading bytes match.</p>
 */
@Service
public class TokenService {

    private static final String ALGORITHM = "HS256";
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final String HEADER_JSON = "{\"alg\":\"" + ALGORITHM + "\",\"typ\":\"JWT\"}";

    private static final String CLAIM_ISSUER = "iss";
    private static final String CLAIM_SUBJECT = "sub";
    private static final String CLAIM_SESSION = "sid";
    private static final String CLAIM_ISSUED_AT = "iat";
    private static final String CLAIM_EXPIRES_AT = "exp";

    private static final int REFRESH_TOKEN_BYTES = 32;

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec signingKey;
    private final ObjectMapper objectMapper;
    private final AuthProperties authProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenService(AuthProperties authProperties, ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;

        String configuredSecret = authProperties.getJwtSecret();
        if (configuredSecret == null || configuredSecret.isBlank()) {
            throw new IllegalStateException(
                    "AUTH_JWT_SECRET must be configured; the service has no default signing key");
        }

        byte[] secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        if (secret.length < AuthProperties.MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "app.auth.jwt-secret must be at least %d bytes for HS256"
                            .formatted(AuthProperties.MIN_SECRET_LENGTH));
        }
        this.signingKey = new SecretKeySpec(secret, MAC_ALGORITHM);
    }

    public IssuedToken issueAccessToken(Long userId, Long sessionId) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(authProperties.getAccessTokenTtl());

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(CLAIM_ISSUER, authProperties.getIssuer());
        claims.put(CLAIM_SUBJECT, String.valueOf(userId));
        claims.put(CLAIM_SESSION, sessionId);
        claims.put(CLAIM_ISSUED_AT, issuedAt.getEpochSecond());
        claims.put(CLAIM_EXPIRES_AT, expiresAt.getEpochSecond());

        String payload = encode(json(claims).getBytes(StandardCharsets.UTF_8));
        String signingInput = encode(HEADER_JSON.getBytes(StandardCharsets.UTF_8)) + "." + payload;
        String signature = BASE64_URL.encodeToString(sign(signingInput));

        return new IssuedToken(signingInput + "." + signature, expiresAt);
    }

    /**
     * @throws UnauthorizedException when the token is malformed, signed with another key, expired
     *                               or uses an unexpected algorithm
     */
    public AccessTokenClaims parseAccessToken(String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Access token is missing");
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new UnauthorizedException("Access token is malformed");
        }

        // Verify first: nothing from the token is trusted before the signature checks out.
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = sign(signingInput);
        byte[] provided;
        try {
            provided = BASE64_URL_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            throw new UnauthorizedException("Access token is malformed");
        }
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new UnauthorizedException("Access token signature is invalid");
        }

        JsonNode header = readTree(parts[0]);
        if (!ALGORITHM.equals(header.path("alg").asText())) {
            throw new UnauthorizedException("Access token uses an unsupported algorithm");
        }

        JsonNode claims = readTree(parts[1]);
        if (!authProperties.getIssuer().equals(claims.path(CLAIM_ISSUER).asText())) {
            throw new UnauthorizedException("Access token was issued by another service");
        }

        long expiresAt = claims.path(CLAIM_EXPIRES_AT).asLong(0);
        if (expiresAt <= 0) {
            throw new UnauthorizedException("Access token has no expiry");
        }
        if (Instant.now().getEpochSecond() >= expiresAt) {
            throw new UnauthorizedException("Access token has expired");
        }

        long userId = parsePositiveLong(claims.path(CLAIM_SUBJECT).asText());
        long sessionId = claims.path(CLAIM_SESSION).asLong(0);
        if (userId <= 0 || sessionId <= 0) {
            throw new UnauthorizedException("Access token is missing its subject");
        }

        return new AccessTokenClaims(userId, sessionId, Instant.ofEpochSecond(expiresAt));
    }

    /** @return a fresh opaque secret; only its {@link #hashToken(String)} value is ever stored */
    public String newRefreshToken() {
        byte[] material = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(material);
        return BASE64_URL.encodeToString(material);
    }

    /** @return lower case hex SHA-256, matching the {@code CHAR(64)} columns of the schema */
    public String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available in this runtime", exception);
        }
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(signingKey);
            return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Unable to sign the access token", exception);
        }
    }

    private JsonNode readTree(String base64UrlSegment) {
        try {
            return objectMapper.readTree(BASE64_URL_DECODER.decode(base64UrlSegment));
        } catch (Exception exception) {
            throw new UnauthorizedException("Access token is malformed");
        }
    }

    private String json(Map<String, Object> claims) {
        try {
            return objectMapper.writeValueAsString(claims);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialise the access token", exception);
        }
    }

    private static String encode(byte[] value) {
        return BASE64_URL.encodeToString(value);
    }

    private static long parsePositiveLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new UnauthorizedException("Access token is missing its subject");
        }
    }

    /**
     * @param token     the signed JWT to hand back to the client
     * @param expiresAt instant the token stops being accepted
     */
    public record IssuedToken(String token, Instant expiresAt) {
    }

    /**
     * @param userId    account the token was issued for
     * @param sessionId session that must still be active
     * @param expiresAt expiry carried by the token
     */
    public record AccessTokenClaims(Long userId, Long sessionId, Instant expiresAt) {
    }
}
