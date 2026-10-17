package net.velocity.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Shared primitives only. Domains own configuration, authenticated bytes, versions and safe errors. */
public final class AesGcm {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcm() {
    }

    public static Map<String, SecretKeySpec> keys(String active, String encoded, String previous) {
        try {
            if (active == null || !active.matches("[A-Za-z0-9_-]{1,64}")
                    || previous == null || previous.length() > 65536) {
                throw new IllegalArgumentException();
            }
            var loaded = new HashMap<String, SecretKeySpec>();
            loaded.put(active, key(encoded));
            if (!previous.isBlank()) {
                var root = JSON.readTree(previous);
                if (root == null || !root.isObject() || root.size() > 15) {
                    throw new IllegalArgumentException();
                }
                var fields = root.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (!field.getKey().matches("[A-Za-z0-9_-]{1,64}")
                            || loaded.containsKey(field.getKey()) || !field.getValue().isTextual()) {
                        throw new IllegalArgumentException();
                    }
                    loaded.put(field.getKey(), key(field.getValue().textValue()));
                }
            }
            return Map.copyOf(loaded);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid encryption key configuration");
        }
    }

    private static SecretKeySpec key(String encoded) {
        byte[] decoded = null;
        try {
            if (encoded == null || encoded.length() != 44) {
                throw new IllegalArgumentException();
            }
            decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
                throw new IllegalArgumentException();
            }
            return new SecretKeySpec(decoded, "AES");
        } finally {
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
    }

    public static Encrypted encrypt(SecretKeySpec key, byte[] plaintext, byte[] authenticated)
            throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, authenticated);
        return new Encrypted(
                Base64.getEncoder().encodeToString(nonce),
                Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)));
    }

    public static byte[] decrypt(SecretKeySpec key, String nonce, String ciphertext, byte[] authenticated)
            throws GeneralSecurityException {
        return cipher(Cipher.DECRYPT_MODE, key, Base64.getDecoder().decode(nonce), authenticated)
                .doFinal(Base64.getDecoder().decode(ciphertext));
    }

    private static Cipher cipher(int mode, SecretKeySpec key, byte[] nonce, byte[] authenticated)
            throws GeneralSecurityException {
        if (key == null || nonce.length != 12) {
            throw new IllegalArgumentException("Invalid encryption payload");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(authenticated);
        return cipher;
    }

    public record Encrypted(String nonce, String ciphertext) {}
}
