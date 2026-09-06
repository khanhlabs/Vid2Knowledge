package com.vid2knowledge.integration;

import com.vid2knowledge.config.IntegrationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
@ConditionalOnProperty(prefix = "integrations", name = "enabled", havingValue = "true")
public class IntegrationSecretCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public IntegrationSecretCipher(IntegrationProperties properties) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(properties.encryptionKey());
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("INTEGRATION_ENCRYPTION_KEY must be base64", failure);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("INTEGRATION_ENCRYPTION_KEY must decode to exactly 32 bytes");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String plaintext, String associatedData) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array()
            );
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Could not encrypt integration secret", failure);
        }
    }

    public String decrypt(String value, String associatedData) {
        if (value == null || !value.startsWith("v1:")) {
            throw new IllegalStateException("Unsupported integration secret format");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(value.substring(3));
            if (combined.length <= IV_BYTES) throw new IllegalArgumentException("Invalid ciphertext");
            byte[] iv = new byte[IV_BYTES];
            byte[] encrypted = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IllegalStateException("Could not decrypt integration secret", failure);
        }
    }
}
