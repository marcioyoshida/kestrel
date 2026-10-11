package org.rundeck.kestrel.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converges the Kestrel-managed CronJobs in a namespace to a desired set: schedule buckets and
 * isolated-job CronJobs. Creates missing ones, replaces ones whose rendered spec changed, and
 * deletes the rest.
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
        return reconcile(desired, List.of());
    }

    /**
     * @param buckets  buckets that must exist
     * @param isolated other CronJobs that must exist (isolated jobs, ADR 0006), each with
     *                 {@link CronJobTemplate#SPEC_HASH} set; every managed CronJob not in either
     *                 set is deleted, so both sets must be passed together
     * @return what changed
     */
    public Result reconcile(Collection<ScheduleBucket> buckets, Collection<ObjectNode> isolated) throws IOException {
        Map<String, ObjectNode> want = new LinkedHashMap<>();
        for (ScheduleBucket bucket : buckets) {
            want.put(bucket.getName(), template.render(bucket));
        }
        for (ObjectNode cj : isolated) {
            want.put(cj.path("metadata").path("name").asText(), cj);
        }
        Map<String, JsonNode> actual = new HashMap<>();
        for (JsonNode item : api.list(collection + "?labelSelector=" + CronJobTemplate.MANAGED_LABEL + "%3Dkestrel").path("items")) {
            actual.put(item.path("metadata").path("name").asText(), item);
        }
        Result result = new Result();
        for (Map.Entry<String, ObjectNode> e : want.entrySet()) {
            String name = e.getKey();
            ObjectNode cj = e.getValue();
            JsonNode have = actual.remove(name);
            if (have == null) {
                try {
                    api.create(collection, cj);
                    result.created++;
                    LOG.info("CronJob {} created", describe(cj));
                } catch (KubeApi.ConflictException ex) {
                    result.conflicts++;  // created concurrently; the next pass compares it
                }
            } else if (!hash(cj).equals(hash(have))) {
                ((ObjectNode) cj.get("metadata")).put("resourceVersion", have.path("metadata").path("resourceVersion").asText());
                try {
                    api.replace(collection + "/" + name, cj);
                    result.updated++;
                    LOG.info("CronJob {} updated", describe(cj));
                } catch (KubeApi.ConflictException ex) {
                    result.conflicts++;
                }
            } else {
                result.unchanged++;
            }
        }
        for (String orphan : actual.keySet()) {
            api.delete(collection + "/" + orphan);
            result.deleted++;
            LOG.info("CronJob {} deleted (no job uses it)", orphan);
        }
        return result;
    }

    private static String describe(JsonNode cj) {
        JsonNode labels = cj.path("metadata").path("labels");
        String job = labels.path(IsolatedRunTemplate.JOB_LABEL).asText("");
        return cj.path("metadata").path("name").asText() + "[" + cj.path("spec").path("schedule").asText() + " "
            + cj.path("spec").path("timeZone").asText() + (job.isEmpty() ? "" : " isolated job " + job) + "]";
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
