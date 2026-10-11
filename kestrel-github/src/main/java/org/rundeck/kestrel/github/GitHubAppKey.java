package org.rundeck.kestrel.github;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * A GitHub App's private key and the short-lived app JWT signed with it (RS256, iat backdated 60 s
 * for clock skew, 9-minute lifetime). GitHub hands out PKCS#1 PEM ({@code BEGIN RSA PRIVATE KEY});
 * PKCS#8 ({@code BEGIN PRIVATE KEY}) is accepted too.
 */
public final class GitHubAppKey {
    private final String appId;
    private final PrivateKey key;

    public GitHubAppKey(String appId, String pem) {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("GitHub App id is required");
        }
        this.appId = appId.trim();
        this.key = parsePem(pem);
    }

    public String appId() {
        return appId;
    }

    public String jwt(Instant now) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(String.format("{\"iat\":%d,\"exp\":%d,\"iss\":\"%s\"}",
                now.getEpochSecond() - 60, now.getEpochSecond() + 540, appId).getBytes(StandardCharsets.UTF_8));
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + b64.encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static PrivateKey parsePem(String pem) {
        if (pem == null || !pem.contains("PRIVATE KEY")) {
            throw new IllegalArgumentException("GitHub App private key must be a PEM private key");
        }
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs1 ? pkcs8(der) : der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("GitHub App private key is not a valid RSA key", e);
        }
    }

    /** Wraps a PKCS#1 RSAPrivateKey in a PKCS#8 PrivateKeyInfo (version 0, rsaEncryption, NULL). */
    private static byte[] pkcs8(byte[] pkcs1) {
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.writeBytes(new byte[]{0x02, 0x01, 0x00});
        inner.writeBytes(algorithm);
        inner.writeBytes(tlv(0x04, pkcs1));
        return tlv(0x30, inner.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int n = value.length;
        if (n < 0x80) {
            out.write(n);
        } else {
            int bytes = n > 0xffffff ? 4 : n > 0xffff ? 3 : n > 0xff ? 2 : 1;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write((n >>> (8 * i)) & 0xff);
            }
        }
        out.writeBytes(value);
        return out.toByteArray();
    }
}
