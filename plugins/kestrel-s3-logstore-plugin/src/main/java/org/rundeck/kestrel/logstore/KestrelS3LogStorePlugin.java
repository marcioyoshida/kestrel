package org.rundeck.kestrel.logstore;

import com.dtolabs.rundeck.core.logging.ExecutionFileStorageException;
import com.dtolabs.rundeck.core.logging.ExecutionFileStorageOptions;
import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.plugins.ServiceNameConstants;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.logging.ExecutionFileStoragePlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Execution log storage on S3 with partial store/retrieve (Kestrel M1). Rundeck checkpoints a
 * running execution's log to {@code <key>.partial} every few seconds (rundeck.execution.logs.
 * fileStorage.checkpoint.*), so a web pod can tail an execution running on a runner pod; the
 * final log is written to {@code <key>} and the partial removed. Keys match the upstream S3 log
 * plugin: {@code project/${job.project}/${job.execid}.<filetype>}.
 */
@Plugin(service = ServiceNameConstants.ExecutionFileStorage, name = KestrelS3LogStorePlugin.PROVIDER)
@PluginDescription(title = "Kestrel S3 log store",
    description = "Stores execution logs in S3, including partial logs of running executions for cross-pod live tail.")
public class KestrelS3LogStorePlugin implements ExecutionFileStoragePlugin, ExecutionFileStorageOptions {
    public static final String PROVIDER = "kestrel-s3";
    static final String DEFAULT_PATH = "project/${job.project}/${job.execid}";
    private static final Pattern VAR = Pattern.compile("\\$\\{job\\.([a-zA-Z0-9_]+)}");

    @PluginProperty(title = "Bucket", description = "S3 bucket (default: env KESTREL_LOG_BUCKET)")
    String bucket;

    @PluginProperty(title = "Region", description = "Bucket region (default: env AWS_REGION)")
    String region;

    @PluginProperty(title = "Path", description = "Object key template without extension", defaultValue = DEFAULT_PATH)
    String path;

    private ObjectStore store;
    private String base;

    /** For tests: use the given object store instead of S3. */
    void setObjectStore(ObjectStore store) {
        this.store = store;
    }

    @Override
    public void initialize(Map<String, ? extends Object> context) {
        base = expand(path == null || path.isBlank() ? DEFAULT_PATH : path, context);
        if (store == null) {
            String b = bucket != null && !bucket.isBlank() ? bucket : System.getenv("KESTREL_LOG_BUCKET");
            String r = region != null && !region.isBlank() ? region : System.getenv("AWS_REGION");
            if (b == null || b.isBlank()) {
                throw new IllegalStateException("kestrel-s3 log store: no bucket (plugin property or KESTREL_LOG_BUCKET)");
            }
            store = new S3ObjectStore(b, r == null || r.isBlank() ? "us-east-1" : r);
        }
    }

    /** Expands ${job.x} from the execution context; unknown variables become empty. */
    static String expand(String template, Map<String, ? extends Object> context) {
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            // As upstream: a retried execution reads and writes the log of the id Rundeck assigns for storage.
            Object logId = context.get("execIdForLogStore");
            Object v = "execid".equals(name) && logId != null && !logId.toString().isBlank() ? logId : context.get(name);
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    String key(String filetype) {
        return base + "." + filetype;
    }

    String partialKey(String filetype) {
        return key(filetype) + ".partial";
    }

    @Override
    public boolean isAvailable(String filetype) throws ExecutionFileStorageException {
        try {
            return store.exists(key(filetype));
        } catch (IOException e) {
            throw new ExecutionFileStorageException(e.getMessage(), e);
        }
    }

    @Override
    public boolean store(String filetype, InputStream stream, long length, Date lastModified) throws IOException {
        store.put(key(filetype), stream, length);
        try {
            store.delete(partialKey(filetype));
        } catch (IOException ignored) {
            // the bucket lifecycle expires leftovers
        }
        return true;
    }

    @Override
    public boolean partialStore(String filetype, InputStream stream, long length, Date lastModified) throws IOException {
        store.put(partialKey(filetype), stream, length);
        return true;
    }

    @Override
    public boolean retrieve(String filetype, OutputStream stream) throws IOException {
        return store.get(key(filetype), stream);
    }

    @Override
    public boolean partialRetrieve(String filetype, OutputStream stream) throws IOException {
        return store.get(partialKey(filetype), stream);
    }

    @Override
    public boolean deleteFile(String filetype) throws IOException {
        store.delete(key(filetype));
        store.delete(partialKey(filetype));
        return true;
    }

    @Override
    public boolean getRetrieveSupported() {
        return true;
    }

    @Override
    public boolean getStoreSupported() {
        return true;
    }

    @Override
    public boolean getPartialRetrieveSupported() {
        return true;
    }

    @Override
    public boolean getPartialStoreSupported() {
        return true;
    }
}
