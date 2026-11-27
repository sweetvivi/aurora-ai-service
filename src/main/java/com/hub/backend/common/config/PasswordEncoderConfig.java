package com.hub.backend.common.config;

import com.hub.backend.common.config.properties.AuthProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Password hashing.
 *
 * <p>New passwords use Argon2id. Existing BCrypt hashes remain verifiable and are upgraded after a
 * successful sign-in. BCrypt input beyond its 72-byte limit is rejected rather than truncated.</p>
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder(AuthProperties authProperties) {
        return new MigratingPasswordEncoder(authProperties);
    }

    static final class MigratingPasswordEncoder implements PasswordEncoder {

        private static final Pattern BCRYPT_HASH =
                Pattern.compile("^\\$2[aby]\\$\\d{2}\\$.{53}$");
        private static final int BCRYPT_MAX_BYTES = 72;

        private final Argon2PasswordEncoder argon2id;
        private final BCryptPasswordEncoder bcrypt;

        private MigratingPasswordEncoder(AuthProperties properties) {
            this.argon2id = new Argon2PasswordEncoder(
                    properties.getArgon2SaltLength(),
                    properties.getArgon2HashLength(),
                    properties.getArgon2Parallelism(),
                    properties.getArgon2MemoryKb(),
                    properties.getArgon2Iterations());
            this.bcrypt = new BCryptPasswordEncoder(properties.getBcryptStrength());
        }

        @Override
        public String encode(CharSequence rawPassword) {
            return argon2id.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            if (rawPassword == null || encodedPassword == null) {
                return false;
            }
            if (encodedPassword.startsWith("$argon2id$")) {
                return argon2id.matches(rawPassword, encodedPassword);
            }
            if (!BCRYPT_HASH.matcher(encodedPassword).matches()) {
                return false;
            }
            if (rawPassword.toString().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
                return false;
            }
            return bcrypt.matches(rawPassword, encodedPassword);
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            return encodedPassword == null || !encodedPassword.startsWith("$argon2id$");
        }
    }
}
