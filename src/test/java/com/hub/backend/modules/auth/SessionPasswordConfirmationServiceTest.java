package com.hub.backend.modules.auth;

import com.hub.backend.common.exception.ForbiddenException;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.PasswordConfirmationRequiredException;
import com.hub.backend.common.security.SessionPasswordVault;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.user.UserService;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionPasswordConfirmationServiceTest {

    private static final CurrentUser USER = new CurrentUser(7L, 1L, "demo");
    private static final String PASSWORD = "correct-horse-battery";

    private UserService users;
    private PasswordEncoder passwordEncoder;
    private SessionPasswordVault passwords;
    private UserAccountEntity account;
    private SessionPasswordConfirmationService service;

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        passwords = mock(SessionPasswordVault.class);
        account = new UserAccountEntity(1L, "demo", "demo");
        account.setPasswordHash("stored-hash");
        when(users.requireById(USER.userId())).thenReturn(account);
        service = new SessionPasswordConfirmationService(users, passwordEncoder, passwords);
    }

    @Test
    void aMissingCandidateAsksTheClientForThePassword() {
        assertThrows(PasswordConfirmationRequiredException.class, () -> service.confirm(USER, null));
        assertThrows(PasswordConfirmationRequiredException.class, () -> service.confirm(USER, "  "));
        verify(passwords, never()).remember(any(), any());
    }

    @Test
    void aVerifiedPasswordIsRememberedForTheSession() {
        when(passwordEncoder.matches(PASSWORD, "stored-hash")).thenReturn(true);

        service.confirm(USER, PASSWORD);

        verify(passwords).remember(USER.userId(), PASSWORD);
    }

    @Test
    void aWrongPasswordIsRejectedAndNeverRemembered() {
        when(passwordEncoder.matches("nope", "stored-hash")).thenReturn(false);

        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class, () -> service.confirm(USER, "nope"));

        assertEquals("当前密码不正确", exception.getMessage());
        verify(passwords, never()).remember(any(), any());
    }

    @Test
    void anOverlongCandidateIsRejectedBeforeHashing() {
        String overlong = "x".repeat(PasswordPolicy.MAX_CODE_POINTS + 1);

        assertThrows(InvalidRequestException.class, () -> service.confirm(USER, overlong));
        verify(passwordEncoder, never()).matches(any(), any());
        verify(passwords, never()).remember(any(), any());
    }

    @Test
    void aDisabledAccountCannotRestoreItsPassword() {
        account.setStatus(UserAccountEntity.STATUS_DISABLED);

        assertThrows(ForbiddenException.class, () -> service.confirm(USER, PASSWORD));
        verify(passwords, never()).remember(any(), any());
    }

    @Test
    void anAccountWithoutAStoredHashCannotConfirmAnything() {
        account.setPasswordHash(null);

        assertThrows(InvalidRequestException.class, () -> service.confirm(USER, PASSWORD));
        verify(passwords, never()).remember(any(), any());
    }
}
