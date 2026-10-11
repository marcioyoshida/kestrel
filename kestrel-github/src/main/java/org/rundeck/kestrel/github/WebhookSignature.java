package org.rundeck.kestrel.github;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** GitHub webhook signatures: {@code X-Hub-Signature-256: sha256=<hex HMAC-SHA256 of the raw body>}. */
public final class WebhookSignature {
    private WebhookSignature() {
    }

    /** @return the header value GitHub sends for this body */
    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return true if the header is the body's signature under the secret (constant-time compare) */
    public static boolean verify(String secret, byte[] body, String header) {
        if (secret == null || secret.isEmpty() || header == null || body == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(secret, body).getBytes(StandardCharsets.US_ASCII),
                header.trim().getBytes(StandardCharsets.US_ASCII));
    }
}
