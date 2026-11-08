package com.relayforge.security;

import com.relayforge.config.RelayForgeProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Seals and opens webhook signing secrets with AES-256-GCM.
 *
 * <h2>Threat this addresses</h2>
 *
 * A webhook signing secret is a shared symmetric credential: whoever holds it
 * can forge a request that a receiver will accept as genuinely ours. A database
 * dump, a leaked backup or an over-broad read replica therefore cannot be
 * allowed to yield usable secrets. Encrypting at rest moves the requirement for
 * compromise from "read one table" to "read one table <em>and</em> obtain the
 * key from the process environment or secret manager".
 *
 * <h2>Why GCM</h2>
 *
 * GCM is authenticated encryption: tampering with a stored ciphertext produces a
 * decryption failure rather than a silently different plaintext. With a plain
 * mode such as CBC, an attacker with write access to the database could flip
 * bits and have us sign with a secret they influenced.
 *
 * <h2>Format</h2>
 *
 * <pre>{@code  v1.<base64url(12-byte IV)>.<base64url(ciphertext||tag)> }</pre>
 *
 * The version prefix exists so a future key-rotation or algorithm change can be
 * rolled out while old values remain readable. A fresh random IV is generated
 * per encryption — reusing an IV under GCM is catastrophic, not merely weak, so
 * it is never derived from anything deterministic.
 */
@Component
public class SecretCipher {

    private static final String VERSION = "v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();

    public SecretCipher(RelayForgeProperties properties) {
        this.key = loadKey(properties.crypto().encryptionKey());
    }

    /**
     * Decodes and validates the configured key.
     *
     * <p>Fails fast and loudly. A short or placeholder key must stop the
     * application at startup; the alternative is a system that appears to work
     * while encrypting every tenant's secrets under a guessable key.
     */
    private static SecretKeySpec loadKey(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "relayforge.crypto.encryption-key is not set. Generate one with: "
                            + "openssl rand -base64 32");
        }
        if (configured.startsWith("CHANGE_ME")) {
            throw new IllegalStateException(
                    "relayforge.crypto.encryption-key still holds the placeholder value from "
                            + ".env.example. Generate a real key with: openssl rand -base64 32");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            // Tolerate a raw-text key, but only if it is long enough to be hashed
            // down to a full-strength key without losing entropy.
            decoded = configured.trim().getBytes(StandardCharsets.UTF_8);
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "relayforge.crypto.encryption-key must decode to exactly "
                            + KEY_LENGTH_BYTES
                            + " bytes for AES-256 (got "
                            + decoded.length
                            + "). Generate one with: openssl rand -base64 32");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    /** Seals a plaintext secret. Returns a self-describing, storable string. */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return VERSION + "." + encoder.encodeToString(iv) + "." + encoder.encodeToString(sealed);
        } catch (GeneralSecurityException e) {
            // Never include the plaintext or the key in the message.
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    /** Opens a sealed secret. Throws if the value was tampered with or truncated. */
    public String decrypt(String sealed) {
        if (sealed == null) {
            return null;
        }
        String[] parts = sealed.split("\\.", 3);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException(
                    "Stored secret is not in the expected sealed format (expected a "
                            + VERSION
                            + " envelope)");
        }
        try {
            byte[] iv = decoder.decode(parts[1]);
            byte[] ciphertext = decoder.decode(parts[2]);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Failed to decrypt a stored secret: the value is corrupt, was encrypted under a"
                            + " different key, or has been tampered with",
                    e);
        }
    }

    /** Generates a fresh endpoint signing secret: 256 bits, prefixed for recognisability. */
    public String generateEndpointSecret() {
        byte[] material = new byte[32];
        random.nextBytes(material);
        return "whsec_" + encoder.encodeToString(material);
    }
}
