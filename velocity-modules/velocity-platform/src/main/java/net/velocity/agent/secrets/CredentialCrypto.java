package net.velocity.agent.secrets;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import net.velocity.agent.io.ErasingByteArrayOutputStream;
import net.velocity.crypto.AesGcm;

/** Environment-backed encryption configuration is separate from MongoDB; invalid keys never reset data. */
public final class CredentialCrypto {

    private final SecretsProperties properties;

    public CredentialCrypto(SecretsProperties properties) {
        this.properties = properties;
    }

    public boolean available() {
        try {
            keys();
            return true;
        } catch (CredentialException unavailable) {
            return false;
        }
    }

    public CredentialRecord.EncryptedPayload encrypt(
            String id,
            String owner,
            long revision,
            String service,
            String label,
            List<String> origins,
            List<String> afterOrigins,
            List<String> applicationOrigins,
            List<String> callbackUrls,
            List<String> resourceOrigins,
            boolean enabled,
            CredentialPair pair) {
        byte[] plaintext = null;
        try {
            Keys keys = keys();
            var bytes = new ErasingByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeUTF(pair.username());
                output.writeUTF(pair.password());
                plaintext = bytes.toByteArray();
            }
            var encrypted = AesGcm.encrypt(
                    keys.keys().get(keys.active()),
                    plaintext,
                    aad(
                            id,
                            owner,
                            revision,
                            service,
                            label,
                            origins,
                            afterOrigins,
                            applicationOrigins,
                            callbackUrls,
                            resourceOrigins,
                            enabled,
                            2));
            return new CredentialRecord.EncryptedPayload(
                    keys.active(),
                    encrypted.nonce(),
                    encrypted.ciphertext(),
                    2);
        } catch (Exception invalid) {
            throw new CredentialException("unavailable");
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    public CredentialPair decrypt(CredentialRecord record) {
        byte[] plaintext = null;
        try {
            plaintext = authenticatedBytes(record);
            try (var input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
                String username = input.readUTF();
                String password = input.readUTF();
                if (input.available() != 0 || username.isEmpty() || username.length() > 2048
                        || password.isEmpty() || password.length() > 4096) {
                    throw new CredentialException("unavailable");
                }
                return new CredentialPair(username, password);
            }
        } catch (Exception invalid) {
            throw new CredentialException("unavailable");
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    /** Recheck the exact payload before a later use of already-filled fields; never materialize credential strings. */
    public void authenticate(CredentialRecord record) {
        byte[] plaintext = null;
        try {
            plaintext = authenticatedBytes(record);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private byte[] authenticatedBytes(CredentialRecord record) {
        try {
            var payload = record.payload();
            var key = keys().keys().get(payload.keyId());
            return AesGcm.decrypt(
                    key,
                    payload.nonce(),
                    payload.ciphertext(),
                    aad(
                            record.credentialId(),
                            record.ownerAccountId(),
                            record.revision(),
                            record.service(),
                            record.accountLabel(),
                            record.credentialOrigins(),
                            record.postLoginOrigins(),
                            record.applicationOrigins(),
                            record.callbackUrls(),
                            record.resourceOrigins(),
                            record.enabled(),
                            payload.policyVersion()));
        } catch (Exception invalid) {
            throw new CredentialException("unavailable");
        }
    }

    private Keys keys() {
        try {
            if (!properties.enabled()) {
                throw new IllegalArgumentException();
            }
            return new Keys(
                    properties.keyId(),
                    AesGcm.keys(properties.keyId(), properties.key(), properties.previousKeys()));
        } catch (Exception invalid) {
            throw new CredentialException("unavailable");
        }
    }

    private static byte[] aad(
            String id,
            String owner,
            long revision,
            String service,
            String label,
            List<String> origins,
            List<String> afterOrigins,
            List<String> applicationOrigins,
            List<String> callbackUrls,
            List<String> resourceOrigins,
            boolean enabled,
            int policyVersion) throws java.io.IOException {
        if (policyVersion != 1 && policyVersion != 2 || policyVersion == 1
                && (!applicationOrigins.isEmpty() || !callbackUrls.isEmpty() || !resourceOrigins.isEmpty())) {
            throw new CredentialException("unavailable");
        }
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeUTF("velocity-credential-aes-gcm-v" + policyVersion);
            output.writeUTF(id);
            output.writeUTF(owner);
            output.writeLong(revision);
            output.writeUTF(service);
            output.writeUTF(label);
            output.writeBoolean(enabled);
            output.writeInt(origins.size());
            for (String origin : origins) {
                output.writeUTF(origin);
            }
            output.writeInt(afterOrigins.size());
            for (String origin : afterOrigins) {
                output.writeUTF(origin);
            }
            if (policyVersion == 2) {
                output.writeInt(applicationOrigins.size());
                for (String origin : applicationOrigins) {
                    output.writeUTF(origin);
                }
                output.writeInt(callbackUrls.size());
                for (String callback : callbackUrls) {
                    output.writeUTF(callback);
                }
                output.writeInt(resourceOrigins.size());
                for (String origin : resourceOrigins) {
                    output.writeUTF(origin);
                }
            }
        }
        return bytes.toByteArray();
    }

    private record Keys(String active, Map<String, SecretKeySpec> keys) {

        @Override
        public String toString() {
            return "Keys[REDACTED]";
        }
    }

}
