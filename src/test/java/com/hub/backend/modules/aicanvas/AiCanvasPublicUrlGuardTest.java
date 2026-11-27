package com.hub.backend.modules.aicanvas;

import com.hub.backend.common.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiCanvasPublicUrlGuardTest {

    @Test
    void acceptsPublicHttpUrls() {
        AiCanvasPublicUrlGuard guard = new AiCanvasPublicUrlGuard(resolver("93.184.216.34"));

        assertEquals(
                URI.create("https://cdn.example.com/a.png"),
                guard.validate("https://cdn.example.com/a.png", "url"));
    }

    @Test
    void rejectsUnsupportedSchemesCredentialsAndBlankValues() {
        AiCanvasPublicUrlGuard guard = new AiCanvasPublicUrlGuard(resolver("93.184.216.34"));

        assertThrows(InvalidRequestException.class, () -> guard.validate(null, "url"));
        assertThrows(InvalidRequestException.class, () -> guard.validate("  ", "url"));
        assertThrows(InvalidRequestException.class, () -> guard.validate("file:///tmp/a.png", "url"));
        assertThrows(InvalidRequestException.class, () -> guard.validate("data:image/png;base64,AAAA", "url"));
        assertThrows(InvalidRequestException.class,
                () -> guard.validate("https://user:pass@example.com/a.png", "url"));
    }

    @Test
    void rejectsPrivateReservedAndMixedResolutionTargets() {
        assertThrows(InvalidRequestException.class,
                () -> new AiCanvasPublicUrlGuard(resolver("127.0.0.1"))
                        .validate("http://127.0.0.1/a.png", "url"));
        assertThrows(InvalidRequestException.class,
                () -> new AiCanvasPublicUrlGuard(resolver("169.254.169.254"))
                        .validate("http://169.254.169.254/latest/meta-data", "url"));
        assertThrows(InvalidRequestException.class,
                () -> new AiCanvasPublicUrlGuard(resolver("10.0.0.5"))
                        .validate("http://internal.example.com/a.png", "url"));
        assertThrows(InvalidRequestException.class,
                () -> new AiCanvasPublicUrlGuard(resolver("93.184.216.34", "10.0.0.5"))
                        .validate("https://mixed.example.com/a.png", "url"));
    }

    @Test
    void rejectsHostsThatCannotBeResolved() {
        AiCanvasPublicUrlGuard.AddressResolver failing = host -> {
            throw new UnknownHostException(host);
        };

        assertThrows(InvalidRequestException.class,
                () -> new AiCanvasPublicUrlGuard(failing).validate("https://nowhere.invalid/a.png", "url"));
    }

    private static AiCanvasPublicUrlGuard.AddressResolver resolver(String... addresses) {
        return host -> {
            InetAddress[] resolved = new InetAddress[addresses.length];
            for (int index = 0; index < addresses.length; index++) {
                resolved[index] = InetAddress.getByName(addresses[index]);
            }
            return resolved;
        };
    }
}
