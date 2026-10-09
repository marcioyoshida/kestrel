package org.rundeck.kestrel.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;

/**
 * {@link KubeApi} over the pod's service account, using only the JDK HTTP client and Jackson.
 * The token is re-read on every call because projected service-account tokens rotate.
 */
public class InClusterKubeApi implements KubeApi {
    private static final Path SA = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI base;
    private final HttpClient http;
    private final Path tokenFile;

    /**
     * @param base      API server URL
     * @param caFile    PEM bundle that signs the API server certificate
     * @param tokenFile bearer token file
     */
    public InClusterKubeApi(URI base, Path caFile, Path tokenFile) throws IOException {
        this.base = base;
        this.tokenFile = tokenFile;
        this.http = HttpClient.newBuilder()
            .sslContext(sslContext(caFile))
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    /**
     * @return a client for the API server this pod runs under
     */
    public static InClusterKubeApi fromEnvironment() throws IOException {
        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv("KUBERNETES_SERVICE_PORT");
        if (host == null || port == null) {
            throw new IOException("not running in Kubernetes (KUBERNETES_SERVICE_HOST unset)");
        }
        String h = host.contains(":") ? "[" + host + "]" : host;
        return new InClusterKubeApi(URI.create("https://" + h + ":" + port), SA.resolve("ca.crt"), SA.resolve("token"));
    }

    private static SSLContext sslContext(Path caFile) throws IOException {
        try (InputStream in = Files.newInputStream(caFile)) {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                ks.setCertificateEntry("ca" + i++, c);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new IOException("cannot load cluster CA " + caFile + ": " + e.getMessage(), e);
        }
    }

    @Override
    public ObjectNode get(String path) throws IOException {
        HttpResponse<String> r = send(request(path).GET());
        if (r.statusCode() == 404) {
            return null;
        }
        return body(r, path);
    }

    @Override
    public ObjectNode list(String collectionPath) throws IOException {
        return body(send(request(collectionPath).GET()), collectionPath);
    }

    @Override
    public ObjectNode create(String collectionPath, ObjectNode body) throws IOException {
        return body(send(request(collectionPath).POST(json(body))), collectionPath);
    }

    @Override
    public ObjectNode replace(String path, ObjectNode body) throws IOException {
        return body(send(request(path).PUT(json(body))), path);
    }

    @Override
    public void delete(String path) throws IOException {
        HttpResponse<String> r = send(request(path)
            .method("DELETE", HttpRequest.BodyPublishers.ofString("{\"propagationPolicy\":\"Background\"}")));
        if (r.statusCode() != 404) {
            body(r, path);
        }
    }

    private HttpRequest.Builder request(String path) throws IOException {
        return HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + Files.readString(tokenFile).trim())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json");
    }

    private static HttpRequest.BodyPublisher json(ObjectNode body) throws IOException {
        return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws IOException {
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static ObjectNode body(HttpResponse<String> r, String path) throws IOException {
        if (r.statusCode() == 409) {
            throw new ConflictException(path + ": " + r.body());
        }
        if (r.statusCode() / 100 != 2) {
            throw new IOException("Kubernetes API " + r.request().method() + " " + path + " -> HTTP " + r.statusCode() + ": " + r.body());
        }
        return (ObjectNode) JSON.readTree(r.body());
    }
}
