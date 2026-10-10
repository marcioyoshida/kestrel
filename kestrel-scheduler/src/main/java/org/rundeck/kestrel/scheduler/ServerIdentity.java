package org.rundeck.kestrel.scheduler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The server UUID of a Kestrel pod. Must match the derivation in the Helm chart's pod command
 * (md5 of {@code kestrel/<namespace>/<pod>}, formatted as a version-3 UUID): the leader uses it
 * to tell which running executions belong to pods that no longer exist.
 */
public final class ServerIdentity {
    private ServerIdentity() {
    }

    /**
     * @param namespace pod namespace
     * @param podName   pod name (stable for StatefulSet pods)
     * @return the server UUID that pod runs with
     */
    public static String uuidFor(String namespace, String podName) {
        String h;
        try {
            h = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest(("kestrel/" + namespace + "/" + podName).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        int variant = (Integer.parseInt(h.substring(16, 17), 16) & 3) | 8;
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-3" + h.substring(13, 16) + "-"
            + Integer.toHexString(variant) + h.substring(17, 20) + "-" + h.substring(20, 32);
    }
}
