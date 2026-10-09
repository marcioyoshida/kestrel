package org.rundeck.kestrel.scheduler;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.TreeMap;

/**
 * Renders the CronJob for a {@link ScheduleBucket}. Each run starts a tiny trigger container
 * that sends one message to the SQS FIFO fire queue; runners do the rest. The trigger derives
 * the scheduled minute from its Job name, which the CronJob controller sets to
 * {@code <cronjob>-<minutes since epoch>}.
 */
public class CronJobTemplate {
    /** Label on every CronJob Kestrel owns; the reconciler never touches anything else. */
    public static final String MANAGED_LABEL = "kestrel.io/managed-by";
    /** Annotation holding a hash of the rendered spec, used to detect drift. */
    public static final String SPEC_HASH = "kestrel.io/spec-hash";

    private final Settings settings;

    /** @param settings deployment-specific values (image, queue, service account…) */
    public CronJobTemplate(Settings settings) {
        this.settings = settings;
    }

    /**
     * @param bucket schedule bucket
     * @return the CronJob object, with the {@link #SPEC_HASH} annotation set
     */
    public ObjectNode render(ScheduleBucket bucket) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode cj = f.objectNode();
        cj.put("apiVersion", "batch/v1");
        cj.put("kind", "CronJob");
        ObjectNode meta = cj.putObject("metadata");
        meta.put("name", bucket.getName());
        ObjectNode labels = meta.putObject("labels");
        labels.put(MANAGED_LABEL, "kestrel");
        labels.put("app.kubernetes.io/part-of", "kestrel");
        labels.put("app.kubernetes.io/component", "trigger");
        ObjectNode ann = meta.putObject("annotations");
        ann.put("kestrel.io/schedule", bucket.getSchedule());
        ann.put("kestrel.io/time-zone", bucket.getTimeZone());

        ObjectNode spec = cj.putObject("spec");
        spec.put("schedule", bucket.getSchedule());
        spec.put("timeZone", bucket.getTimeZone());
        spec.put("concurrencyPolicy", "Allow");  // overlap is decided per job by the runner
        spec.put("startingDeadlineSeconds", settings.startingDeadlineSeconds);
        spec.put("successfulJobsHistoryLimit", 1);
        spec.put("failedJobsHistoryLimit", 2);
        ObjectNode jobSpec = spec.putObject("jobTemplate").putObject("spec");
        jobSpec.put("backoffLimit", 4);
        jobSpec.put("activeDeadlineSeconds", 300);
        jobSpec.put("ttlSecondsAfterFinished", 600);
        ObjectNode pod = jobSpec.putObject("template");
        pod.putObject("metadata").putObject("labels")
            .put("app.kubernetes.io/part-of", "kestrel")
            .put("app.kubernetes.io/component", "trigger");
        ObjectNode ps = pod.putObject("spec");
        ps.put("serviceAccountName", settings.serviceAccount);
        ps.put("restartPolicy", "OnFailure");
        ps.put("automountServiceAccountToken", false);  // Pod Identity uses its own projected token
        ObjectNode psc = ps.putObject("securityContext");
        psc.put("runAsNonRoot", true);
        psc.put("runAsUser", 65532);
        psc.putObject("seccompProfile").put("type", "RuntimeDefault");
        if (!settings.nodeSelector.isEmpty()) {
            ObjectNode ns = ps.putObject("nodeSelector");
            settings.nodeSelector.forEach(ns::put);
        }
        ObjectNode c = ps.putArray("containers").addObject();
        c.put("name", "trigger");
        c.put("image", settings.image);
        ArrayNode env = c.putArray("env");
        env.addObject().put("name", "KESTREL_QUEUE_URL").put("value", settings.queueUrl);
        env.addObject().put("name", "AWS_REGION").put("value", settings.region);
        env.addObject().put("name", "KESTREL_BUCKET").put("value", bucket.getName());
        env.addObject().put("name", "KESTREL_SCHEDULE").put("value", bucket.getSchedule());
        env.addObject().put("name", "KESTREL_TIME_ZONE").put("value", bucket.getTimeZone());
        env.addObject().put("name", "KESTREL_JOB_NAME").putObject("valueFrom").putObject("fieldRef")
            .put("fieldPath", "metadata.labels['batch.kubernetes.io/job-name']");
        ObjectNode res = c.putObject("resources");
        res.putObject("requests").put("cpu", "10m").put("memory", "16Mi");
        res.putObject("limits").put("memory", "32Mi");
        ObjectNode sc = c.putObject("securityContext");
        sc.put("allowPrivilegeEscalation", false);
        sc.put("readOnlyRootFilesystem", true);
        sc.putObject("capabilities").putArray("drop").add("ALL");

        ann.put(SPEC_HASH, ScheduleBucket.digest(spec.toString() + labels).substring(0, 16));
        return cj;
    }

    /** Values that vary per installation. */
    public static class Settings {
        final String image;
        final String serviceAccount;
        final String queueUrl;
        final String region;
        final int startingDeadlineSeconds;
        final Map<String, String> nodeSelector;

        /**
         * @param image                   trigger image
         * @param serviceAccount          service account with SQS send permission (Pod Identity)
         * @param queueUrl                SQS FIFO fire queue URL
         * @param region                  AWS region of the queue
         * @param startingDeadlineSeconds how late a missed run may still start
         * @param nodeSelector            node selector for trigger pods (may be empty)
         */
        public Settings(String image, String serviceAccount, String queueUrl, String region,
                        int startingDeadlineSeconds, Map<String, String> nodeSelector)
        {
            this.image = image;
            this.serviceAccount = serviceAccount;
            this.queueUrl = queueUrl;
            this.region = region;
            this.startingDeadlineSeconds = startingDeadlineSeconds;
            this.nodeSelector = new TreeMap<>(nodeSelector);
        }
    }
}
