package org.rundeck.kestrel.logstore;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** {@link ObjectStore} on one S3 bucket; credentials from the default chain (EKS Pod Identity). */
public class S3ObjectStore implements ObjectStore {
    private final S3Client s3;
    private final String bucket;

    /**
     * @param bucket bucket name
     * @param region bucket region
     */
    public S3ObjectStore(String bucket, String region) {
        this.bucket = bucket;
        this.s3 = S3Client.builder().region(Region.of(region)).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    @Override
    public void put(String key, InputStream data, long length) throws IOException {
        try {
            s3.putObject(b -> b.bucket(bucket).key(key).contentType("application/octet-stream"),
                RequestBody.fromInputStream(data, length));
        } catch (S3Exception e) {
            throw new IOException("S3 put " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean get(String key, OutputStream out) throws IOException {
        try (var in = s3.getObject(b -> b.bucket(bucket).key(key))) {
            in.transferTo(out);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            throw new IOException("S3 get " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String key) throws IOException {
        try {
            s3.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new IOException("S3 head " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(key));
        } catch (S3Exception e) {
            throw new IOException("S3 delete " + key + ": " + e.getMessage(), e);
        }
    }
}
