package com.hub.backend.modules.admin.provider;

import com.hub.backend.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminGuardTest {

    @Test
    void requiresTheConfiguredKeyEvenWhenTheLegacyToggleIsOff() {
        AdminProperties properties = new AdminProperties();
        properties.setApiKey("admin-secret");
        properties.setRequireApiKey(false);
        AdminGuard guard = new AdminGuard(properties);

        assertThrows(UnauthorizedException.class,
                () -> guard.require(new MockHttpServletRequest()));
    }

    @Test
    void rejectsAWrongKey() {
        AdminProperties properties = new AdminProperties();
        properties.setApiKey("admin-secret");
        AdminGuard guard = new AdminGuard(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Admin-Key", "not-the-key");

        assertThrows(UnauthorizedException.class, () -> guard.require(request));
    }

    @Test
    void acceptsTheConfiguredKey() {
        AdminProperties properties = new AdminProperties();
        properties.setApiKey("admin-secret");
        AdminGuard guard = new AdminGuard(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Admin-Key", "admin-secret");

        assertDoesNotThrow(() -> guard.require(request));
    }

    @Test
    void refusesEveryRequestWhenNoKeyIsConfigured() {
        AdminProperties properties = new AdminProperties();
        AdminGuard guard = new AdminGuard(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Admin-Key", "");

        UnauthorizedException exception = assertThrows(UnauthorizedException.class,
                () -> guard.require(request));

        assertEquals("Admin API key is not configured", exception.getMessage());
    }
}
