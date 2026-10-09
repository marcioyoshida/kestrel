package org.rundeck.kestrel.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Converges the Kestrel-managed CronJobs in a namespace to a desired set of schedule buckets:
 * creates missing ones, replaces ones whose rendered spec changed, and deletes the rest.
 * Only objects labelled {@code kestrel.io/managed-by=kestrel} are ever modified. Idempotent;
 * run it from the leader only.
 */
public class CronJobReconciler {
    private static final Logger LOG = LoggerFactory.getLogger(CronJobReconciler.class);

    private final KubeApi api;
    private final String collection;
    private final CronJobTemplate template;

    /**
     * @param api       Kubernetes API
     * @param namespace namespace for the CronJobs
     * @param template  CronJob renderer
     */
    public CronJobReconciler(KubeApi api, String namespace, CronJobTemplate template) {
        this.api = api;
        this.collection = "/apis/batch/v1/namespaces/" + namespace + "/cronjobs";
        this.template = template;
    }

    /**
     * @param desired buckets that must exist
     * @return what changed
     */
    public Result reconcile(Collection<ScheduleBucket> desired) throws IOException {
        Map<String, JsonNode> actual = new HashMap<>();
        for (JsonNode item : api.list(collection + "?labelSelector=" + CronJobTemplate.MANAGED_LABEL + "%3Dkestrel").path("items")) {
            actual.put(item.path("metadata").path("name").asText(), item);
        }
        Result result = new Result();
        for (ScheduleBucket bucket : desired) {
            ObjectNode want = template.render(bucket);
            JsonNode have = actual.remove(bucket.getName());
            if (have == null) {
                try {
                    api.create(collection, want);
                    result.created++;
                    LOG.info("CronJob {} created", bucket);
                } catch (KubeApi.ConflictException e) {
                    result.conflicts++;  // created concurrently; the next pass compares it
                }
            } else if (!hash(want).equals(hash(have))) {
                ((ObjectNode) want.get("metadata")).put("resourceVersion", have.path("metadata").path("resourceVersion").asText());
                try {
                    api.replace(collection + "/" + bucket.getName(), want);
                    result.updated++;
                    LOG.info("CronJob {} updated", bucket);
                } catch (KubeApi.ConflictException e) {
                    result.conflicts++;
                }
            } else {
                result.unchanged++;
            }
        }
        for (String orphan : actual.keySet()) {
            api.delete(collection + "/" + orphan);
            result.deleted++;
            LOG.info("CronJob {} deleted (no job uses its schedule)", orphan);
        }
        return result;
    }

    private static String hash(JsonNode cronJob) {
        return cronJob.path("metadata").path("annotations").path(CronJobTemplate.SPEC_HASH).asText("");
    }

    /** Counts of what one reconcile pass did. */
    public static class Result {
        public int created;
        public int updated;
        public int deleted;
        public int unchanged;
        public int conflicts;

        /** @return true if anything was created, updated or deleted */
        public boolean changed() {
            return created + updated + deleted > 0;
        }

        @Override
        public String toString() {
            return "created=" + created + " updated=" + updated + " deleted=" + deleted
                + " unchanged=" + unchanged + " conflicts=" + conflicts;
        }
    }
}
