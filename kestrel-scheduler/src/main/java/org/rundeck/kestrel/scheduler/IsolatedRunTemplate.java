package org.rundeck.kestrel.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Renders isolated runs (ADR 0006): a Kubernetes Job that runs exactly one execution, or a
 * CronJob whose every firing is such a Job. Both start from the runner class's PodTemplate,
 * which the Helm chart renders with the image, configuration and service account of that class.
 *
 * <p>The pod's server UUID is derived from its Job name (the chart sets {@code KESTREL_POD_NAME}
 * from the {@code batch.kubernetes.io/job-name} label), so a retried pod of the same Job has the
 * same identity: its boot marks a run the previous pod left behind incomplete instead of
 * starting it twice.
 */
public class IsolatedRunTemplate {
    /** {@code app.kubernetes.io/component} of isolated runs and their CronJobs. */
    public static final String COMPONENT = "isolated";
    /** Label: UUID of the Rundeck job. */
    public static final String JOB_LABEL = "kestrel.io/job";
    /** Label: runner class. */
    public static final String CLASS_LABEL = "kestrel.io/runner-class";
    /** Label: execution id of a run-now Job. */
    public static final String EXECUTION_LABEL = "kestrel.io/execution";
    /** PodTemplate annotation: the Job's {@code activeDeadlineSeconds}. */
    public static final String DEADLINE_ANNOTATION = "kestrel.io/active-deadline-seconds";
    /** Environment: job UUID a CronJob firing runs. */
    public static final String ENV_JOB = "KESTREL_ISOLATED_JOB";
    /** Environment: name of the CronJob that created this Job (its name carries the scheduled minute). */
    public static final String ENV_CRONJOB = "KESTREL_ISOLATED_CRONJOB";
    /** Environment: execution id a run-now Job runs. */
    public static final String ENV_EXECUTION = "KESTREL_ISOLATED_EXECUTION";
    /** Where the Secret with run-time secure option values is mounted. */
    public static final String SECURE_OPTIONS_DIR = "/run/kestrel";
    /** File in {@link #SECURE_OPTIONS_DIR}: JSON {@code {"secureOpts":{..},"secureOptsExposed":{..}}}. */
    public static final String SECURE_OPTIONS_FILE = "secure-options.json";
    /** Exit code of an isolated pod whose execution did not succeed: fails the Job without a retry. */
    public static final int EXIT_EXECUTION_FAILED = 3;
    /** Name of the Rundeck container in the PodTemplate. */
    public static final String CONTAINER = "rundeck";

    static final int BACKOFF_LIMIT = 2;
    static final int TTL_SECONDS_AFTER_FINISHED = 3600;
    static final long DEFAULT_DEADLINE_SECONDS = 86400;

    private final String namespace;
    private final int startingDeadlineSeconds;

    /**
     * @param namespace               namespace of the Jobs (the release's)
     * @param startingDeadlineSeconds how late a missed CronJob firing may still start
     */
    public IsolatedRunTemplate(String namespace, int startingDeadlineSeconds) {
        this.namespace = namespace;
        this.startingDeadlineSeconds = startingDeadlineSeconds;
    }

    /** @return name of the CronJob of an isolated job; stable across replicas */
    public static String cronJobName(String jobUuid) {
        return ScheduleBucket.NAME_PREFIX + "i-" + ScheduleBucket.digest(jobUuid).substring(0, 12);
    }

    /**
     * @param executionId execution
     * @param suffix      random suffix, so a reused id (a recreated table) never meets an old Job
     * @return name of the Job running one execution
     */
    public static String runJobName(long executionId, String suffix) {
        return ScheduleBucket.NAME_PREFIX + "run-" + executionId + "-" + suffix;
    }

    /**
     * @param podTemplate the class's PodTemplate object
     * @param runnerClass class name
     * @param jobName     Job name
     * @param jobUuid     Rundeck job
     * @param executionId execution to run (status {@code queued})
     * @param secretName  Secret holding secure option values, or null if there are none
     * @return the Job
     */
    public ObjectNode renderRun(JsonNode podTemplate, String runnerClass, String jobName, String jobUuid,
                                long executionId, String secretName)
    {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode job = f.objectNode();
        job.put("apiVersion", "batch/v1");
        job.put("kind", "Job");
        ObjectNode meta = job.putObject("metadata");
        meta.put("name", jobName);
        meta.put("namespace", namespace);
        ObjectNode labels = labels(meta.putObject("labels"), runnerClass, jobUuid);
        labels.put(EXECUTION_LABEL, Long.toString(executionId));
        ObjectNode spec = jobSpec(podTemplate, runnerClass, jobUuid);
        ObjectNode container = container(spec);
        env(container, ENV_EXECUTION, Long.toString(executionId));
        ((ObjectNode) spec.at("/template/metadata/labels")).put(EXECUTION_LABEL, Long.toString(executionId));
        if (secretName != null) {
            ObjectNode podSpec = (ObjectNode) spec.at("/template/spec");
            array(podSpec, "volumes").addObject()
                .put("name", "kestrel-run")
                .putObject("secret").put("secretName", secretName).put("optional", false);
            array(container, "volumeMounts").addObject()
                .put("name", "kestrel-run").put("mountPath", SECURE_OPTIONS_DIR).put("readOnly", true);
        }
        job.set("spec", spec);
        return job;
    }

    /**
     * @param podTemplate the class's PodTemplate object
     * @param runnerClass class name
     * @param schedule    the job's schedule
     * @param jobUuid     Rundeck job
     * @return the CronJob, managed by {@link CronJobReconciler} ({@link CronJobTemplate#SPEC_HASH} set)
     */
    public ObjectNode renderCronJob(JsonNode podTemplate, String runnerClass, ScheduleBucket schedule, String jobUuid) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        String name = cronJobName(jobUuid);
        ObjectNode cj = f.objectNode();
        cj.put("apiVersion", "batch/v1");
        cj.put("kind", "CronJob");
        ObjectNode meta = cj.putObject("metadata");
        meta.put("name", name);
        ObjectNode labels = labels(meta.putObject("labels"), runnerClass, jobUuid);
        labels.put(CronJobTemplate.MANAGED_LABEL, "kestrel");
        ObjectNode ann = meta.putObject("annotations");
        ann.put("kestrel.io/schedule", schedule.getSchedule());
        ann.put("kestrel.io/time-zone", schedule.getTimeZone());

        ObjectNode spec = cj.putObject("spec");
        spec.put("schedule", schedule.getSchedule());
        spec.put("timeZone", schedule.getTimeZone());
        spec.put("concurrencyPolicy", "Allow");  // overlap is decided by the job's own setting
        spec.put("startingDeadlineSeconds", startingDeadlineSeconds);
        spec.put("successfulJobsHistoryLimit", 1);
        spec.put("failedJobsHistoryLimit", 2);
        ObjectNode jobSpec = jobSpec(podTemplate, runnerClass, jobUuid);
        ObjectNode container = container(jobSpec);
        env(container, ENV_JOB, jobUuid);
        env(container, ENV_CRONJOB, name);
        ObjectNode jt = spec.putObject("jobTemplate");
        jt.putObject("metadata").set("labels", labels.deepCopy().without(CronJobTemplate.MANAGED_LABEL));
        jt.set("spec", jobSpec);

        ann.put(CronJobTemplate.SPEC_HASH, ScheduleBucket.digest(spec.toString() + labels).substring(0, 16));
        return cj;
    }

    /**
     * The Secret carrying a run's secure option values, owned by its Job: Kubernetes deletes it
     * with the Job.
     *
     * @param job  the created Job (with {@code metadata.uid})
     * @param json content of {@link #SECURE_OPTIONS_FILE}
     * @return the Secret
     */
    public ObjectNode renderSecret(JsonNode job, String json) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode s = f.objectNode();
        s.put("apiVersion", "v1");
        s.put("kind", "Secret");
        s.put("type", "Opaque");
        ObjectNode meta = s.putObject("metadata");
        meta.put("name", job.at("/metadata/name").asText());
        meta.putObject("labels")
            .put("app.kubernetes.io/part-of", "kestrel")
            .put("app.kubernetes.io/component", COMPONENT);
        meta.putArray("ownerReferences").addObject()
            .put("apiVersion", "batch/v1")
            .put("kind", "Job")
            .put("name", job.at("/metadata/name").asText())
            .put("uid", job.at("/metadata/uid").asText())
            .put("blockOwnerDeletion", false);
        s.putObject("data").put(SECURE_OPTIONS_FILE,
            Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
        return s;
    }

    private ObjectNode jobSpec(JsonNode podTemplate, String runnerClass, String jobUuid) {
        JsonNode template = podTemplate.path("template");
        if (!template.path("spec").path("containers").isArray()) {
            throw new IllegalArgumentException("PodTemplate " + podTemplate.at("/metadata/name").asText()
                + " has no template.spec.containers");
        }
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode spec = f.objectNode();
        spec.put("backoffLimit", BACKOFF_LIMIT);
        spec.put("activeDeadlineSeconds", podTemplate.at("/metadata/annotations")
            .path(DEADLINE_ANNOTATION).asLong(DEFAULT_DEADLINE_SECONDS));
        spec.put("ttlSecondsAfterFinished", TTL_SECONDS_AFTER_FINISHED);
        ArrayNode rules = spec.putObject("podFailurePolicy").putArray("rules");
        // The execution ran and did not succeed: that is the job's result, not a reason to retry.
        ObjectNode failed = rules.addObject();
        failed.put("action", "FailJob");
        ObjectNode codes = failed.putObject("onExitCodes");
        codes.put("containerName", CONTAINER);
        codes.put("operator", "In");
        codes.putArray("values").add(EXIT_EXECUTION_FAILED);
        // Spot interruption, eviction, preemption: retry without spending the backoff budget.
        rules.addObject().put("action", "Ignore")
            .putArray("onPodConditions").addObject().put("type", "DisruptionTarget");
        ObjectNode pod = ((ObjectNode) template).deepCopy();
        ObjectNode podMeta = pod.has("metadata") ? (ObjectNode) pod.get("metadata") : pod.putObject("metadata");
        ObjectNode podLabels = podMeta.has("labels") ? (ObjectNode) podMeta.get("labels") : podMeta.putObject("labels");
        labels(podLabels, runnerClass, jobUuid);
        ((ObjectNode) pod.get("spec")).put("restartPolicy", "Never");
        spec.set("template", pod);
        return spec;
    }

    private static ObjectNode labels(ObjectNode labels, String runnerClass, String jobUuid) {
        labels.put("app.kubernetes.io/part-of", "kestrel");
        labels.put("app.kubernetes.io/component", COMPONENT);
        labels.put(JOB_LABEL, jobUuid);
        labels.put(CLASS_LABEL, runnerClass);
        return labels;
    }

    private static ObjectNode container(ObjectNode jobSpec) {
        for (JsonNode c : jobSpec.at("/template/spec/containers")) {
            if (CONTAINER.equals(c.path("name").asText())) {
                return (ObjectNode) c;
            }
        }
        throw new IllegalArgumentException("PodTemplate has no container named " + CONTAINER);
    }

    private static void env(ObjectNode container, String name, String value) {
        ArrayNode env = array(container, "env");
        for (int i = env.size() - 1; i >= 0; i--) {
            if (name.equals(env.get(i).path("name").asText())) {
                env.remove(i);
            }
        }
        env.addObject().put("name", name).put("value", value);
    }

    private static ArrayNode array(ObjectNode o, String field) {
        return o.has(field) && o.get(field).isArray() ? (ArrayNode) o.get(field) : o.putArray(field);
    }

    /**
     * Labels selecting every isolated Job of a namespace.
     *
     * @return label selector, URL-encoded
     */
    public static String jobSelector() {
        return "app.kubernetes.io%2Fcomponent%3D" + COMPONENT;
    }

    /**
     * @param job Job object
     * @return true once the Job has finished (condition Complete or Failed is True)
     */
    public static boolean finished(JsonNode job) {
        for (JsonNode c : job.at("/status/conditions")) {
            String type = c.path("type").asText();
            if (("Complete".equals(type) || "Failed".equals(type)) && "True".equals(c.path("status").asText())) {
                return true;
            }
        }
        return false;
    }
}
