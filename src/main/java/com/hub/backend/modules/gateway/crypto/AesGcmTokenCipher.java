package com.hub.backend.modules.gateway.crypto;

import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.ServiceUnavailableException;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AesGcmTokenCipher {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;

    private final NewApiProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmTokenCipher(NewApiProperties properties) {
        this.properties = properties;
    }

    public EncryptedToken encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new ServiceUnavailableException("New API relay key is empty");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = new byte[sealed.length - TAG_BYTES];
            byte[] tag = new byte[TAG_BYTES];
            System.arraycopy(sealed, 0, ciphertext, 0, ciphertext.length);
            System.arraycopy(sealed, ciphertext.length, tag, 0, TAG_BYTES);
            return new EncryptedToken(ciphertext, nonce, tag);
        } catch (GeneralSecurityException exception) {
            throw new ServiceUnavailableException("Unable to encrypt the New API relay key");
        }
    }

    public String decrypt(byte[] ciphertext, byte[] nonce, byte[] tag) {
        if (ciphertext == null || nonce == null || tag == null
                || nonce.length != NONCE_BYTES || tag.length != TAG_BYTES) {
            throw new ServiceUnavailableException("Stored New API relay key is invalid");
        }
        byte[] sealed = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, sealed, 0, ciphertext.length);
        System.arraycopy(tag, 0, sealed, ciphertext.length, tag.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new ServiceUnavailableException("Stored New API relay key cannot be decrypted");
        }
    }

    private byte[] key() {
        String configured = properties.getEncryptionKey();
        if (configured == null || configured.isBlank()) {
            throw new ServiceUnavailableException(
                    "NEW_API_ENCRYPTION_KEY is required for user relay credentials");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(configured.strip());
            if (decoded.length != KEY_BYTES) {
                throw new ServiceUnavailableException(
                        "NEW_API_ENCRYPTION_KEY must decode to exactly 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new ServiceUnavailableException(
                    "NEW_API_ENCRYPTION_KEY must be valid Base64");
        }
    }

    public record EncryptedToken(byte[] ciphertext, byte[] nonce, byte[] tag) {
    }
}
