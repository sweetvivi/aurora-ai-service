package com.hub.backend.modules.auth;

import com.hub.backend.common.config.properties.AppProperties;
import com.hub.backend.common.config.properties.SecurityProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.UnauthorizedException;
import com.hub.backend.modules.appconfig.AppSiteService;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.user.UserService;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CurrentUserResolverTest {

    private static final long SITE_ID = 1L;
    private static final long DEMO_ID = 7L;
    private static final long OTHER_SITE = 2L;
    private static final String ACCESS_TOKEN = "header.payload.signature";

    @Mock
    private AppSiteService appSiteService;
    @Mock
    private UserService userService;
    @Mock
    private TokenService tokenService;
    @Mock
    private AuthService authService;

    private AppProperties appProperties;
    private SecurityProperties securityProperties;
    private CurrentUserResolver resolver;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        securityProperties = new SecurityProperties();
        securityProperties.setTrustUserHeader(true);
        resolver = new CurrentUserResolver(tokenService, authService, appSiteService,
                userService, appProperties, securityProperties);
        when(appSiteService.requireSiteId("default")).thenReturn(SITE_ID);
    }

    // ------------------------------------------------------------------ access token

    @Test
    void prefersTheAccessTokenOverTheDevelopmentHeader() {
        MockHttpServletRequest request = requestWithToken();
        request.addHeader("X-User-Id", "999");
        TokenService.AccessTokenClaims claims = claims(31L, 9L);
        when(tokenService.parseAccessToken(ACCESS_TOKEN)).thenReturn(claims);
        when(authService.resolveCurrentUser(claims)).thenReturn(new CurrentUser(31L, SITE_ID, "alice"));

        CurrentUser currentUser = resolver.require(request);

        assertEquals(31L, currentUser.userId().longValue());
        assertEquals("alice", currentUser.username());
    }

    @Test
    void rejectsAMalformedOrExpiredTokenInsteadOfFallingBack() {
        MockHttpServletRequest request = requestWithToken();
        when(tokenService.parseAccessToken(ACCESS_TOKEN))
                .thenThrow(new UnauthorizedException("Access token signature is invalid"));

        assertThrows(UnauthorizedException.class, () -> resolver.require(request));
    }

    @Test
    void requestResolutionNeverFallsBackToTheDeveloperHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "31");

        assertThrows(UnauthorizedException.class, () -> resolver.require(request));
    }

    @Test
    void requireAuthenticatedRejectsARequestWithoutAToken() {
        UnauthorizedException exception = assertThrows(UnauthorizedException.class,
                () -> resolver.requireAuthenticated(new MockHttpServletRequest()));

        assertEquals("请先登录后再试", exception.getMessage());
    }

    @Test
    void requireAuthenticatedRejectsARequestWithAnEmptyBearerValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer    ");

        assertThrows(UnauthorizedException.class, () -> resolver.requireAuthenticated(request));
    }

    @Test
    void requireClaimsRejectsARequestWithoutAToken() {
        assertThrows(UnauthorizedException.class, () -> resolver.requireClaims(new MockHttpServletRequest()));
    }

    @Test
    void requireClaimsReturnsTheVerifiedClaims() {
        MockHttpServletRequest request = requestWithToken();
        TokenService.AccessTokenClaims claims = claims(31L, 9L);
        when(tokenService.parseAccessToken(ACCESS_TOKEN)).thenReturn(claims);
        when(authService.resolveCurrentUser(claims)).thenReturn(new CurrentUser(31L, SITE_ID, "alice"));

        assertEquals(9L, resolver.requireClaims(request).sessionId().longValue());
    }

    @Test
    void matchesTheBearerSchemeCaseInsensitively() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "bearer " + ACCESS_TOKEN);
        TokenService.AccessTokenClaims claims = claims(31L, 9L);
        when(tokenService.parseAccessToken(ACCESS_TOKEN)).thenReturn(claims);
        when(authService.resolveCurrentUser(claims)).thenReturn(new CurrentUser(31L, SITE_ID, "alice"));

        assertEquals(31L, resolver.require(request).userId().longValue());
    }

    // ------------------------------------------------------------------ development fallbacks

    @Test
    void fallsBackToTheConfiguredAccountWhenNoHeaderIsSent() {
        UserAccountEntity demo = account(DEMO_ID, SITE_ID, "demo");
        when(userService.resolveDefaultUser(SITE_ID, "demo")).thenReturn(demo);

        CurrentUser currentUser = resolver.require((String) null);

        assertEquals(DEMO_ID, currentUser.userId().longValue());
        assertEquals("demo", currentUser.username());
    }

    @Test
    void blankHeaderAlsoFallsBackToTheConfiguredAccount() {
        UserAccountEntity demo = account(DEMO_ID, SITE_ID, "demo");
        when(userService.resolveDefaultUser(SITE_ID, "demo")).thenReturn(demo);

        assertEquals(DEMO_ID, resolver.require("   ").userId().longValue());
    }

    @Test
    void honoursTheHeaderWhileTrustIsEnabled() {
        UserAccountEntity someone = account(31L, SITE_ID, "someone");
        when(userService.requireById(31L)).thenReturn(someone);

        assertEquals(31L, resolver.require("31").userId().longValue());
    }

    @Test
    void ignoresTheHeaderWhenTrustIsDisabled() {
        securityProperties.setTrustUserHeader(false);
        UserAccountEntity demo = account(DEMO_ID, SITE_ID, "demo");
        when(userService.resolveDefaultUser(SITE_ID, "demo")).thenReturn(demo);

        assertEquals(DEMO_ID, resolver.require("31").userId().longValue());
    }

    @Test
    void rejectsANonNumericHeader() {
        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> resolver.require("abc"));

        assertEquals("X-User-Id must be a numeric account id", exception.getMessage());
    }

    @Test
    void rejectsAnUnknownAccount() {
        when(userService.requireById(999L)).thenThrow(new NotFoundException("User 999 was not found"));

        assertThrows(NotFoundException.class, () -> resolver.require("999"));
    }

    @Test
    void rejectsAnAccountFromAnotherSite() {
        UserAccountEntity foreign = account(44L, OTHER_SITE, "foreign");
        when(userService.requireById(44L)).thenReturn(foreign);

        NotFoundException exception = assertThrows(NotFoundException.class, () -> resolver.require("44"));

        assertEquals("User 44 was not found", exception.getMessage());
    }

    private static MockHttpServletRequest requestWithToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN);
        return request;
    }

    private static TokenService.AccessTokenClaims claims(long userId, long sessionId) {
        return new TokenService.AccessTokenClaims(userId, sessionId, Instant.now().plusSeconds(600));
    }

    private static UserAccountEntity account(long id, long siteId, String username) {
        UserAccountEntity account = mock(UserAccountEntity.class);
        when(account.getId()).thenReturn(id);
        when(account.getSiteId()).thenReturn(siteId);
        when(account.getUsername()).thenReturn(username);
        return account;
    }
}
