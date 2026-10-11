package org.rundeck.kestrel.app.isolated

import com.dtolabs.rundeck.core.schedule.JobScheduleFailure
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import groovy.util.logging.Slf4j
import org.rundeck.kestrel.app.KestrelSettings
import org.rundeck.kestrel.scheduler.InClusterKubeApi
import org.rundeck.kestrel.scheduler.IsolatedRunTemplate
import org.rundeck.kestrel.scheduler.KubeApi
import org.rundeck.kestrel.scheduler.ScheduleBucket
import org.rundeck.kestrel.scheduler.ServerIdentity
import rundeck.Execution
import rundeck.ScheduledExecution
import rundeck.services.ExecutionService

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Isolated runs on web pods (ADR 0006): a run-now of an isolated job becomes a Kubernetes Job,
 * and the leader renders one CronJob per scheduled isolated job. Both come from the runner
 * class's PodTemplate, which the Helm chart renders.
 */
@Slf4j
class KestrelIsolated {
    static final ObjectMapper JSON = new ObjectMapper()
    static final long TEMPLATE_CACHE_MS = 30_000L

    KestrelSettings kestrelSettings
    private volatile KubeApi kubeApi
    private final Map<String, Map> templates = new ConcurrentHashMap<>()
    private final SecureRandom random = new SecureRandom()

    /**
     * @param se job
     * @return the job's runner class, or null if the job is not isolated
     */
    static String runnerClass(ScheduledExecution se) {
        def lifecycle = se?.pluginConfigMap?.get('ExecutionLifecycle')
        if (!(lifecycle instanceof Map) || !((Map) lifecycle).containsKey(KestrelIsolatedPlugin.PROVIDER)) {
            return null
        }
        def config = ((Map) lifecycle).get(KestrelIsolatedPlugin.PROVIDER)
        String cls = config instanceof Map ? config.get(KestrelIsolatedPlugin.PROPERTY)?.toString()?.trim() : null
        cls ?: 'default'
    }

    /** For tests and the local harness. */
    void setKubeApi(KubeApi api) {
        kubeApi = api
    }

    private KubeApi api() {
        if (kubeApi == null) {
            kubeApi = InClusterKubeApi.fromEnvironment()
        }
        kubeApi
    }

    private IsolatedRunTemplate template() {
        new IsolatedRunTemplate(kestrelSettings.namespace, kestrelSettings.startingDeadlineSeconds)
    }

    /**
     * @param runnerClass class
     * @return the class's PodTemplate (cached 30 s)
     * @throws JobScheduleFailure if the class is not configured or its PodTemplate is missing
     */
    JsonNode podTemplate(String runnerClass) throws JobScheduleFailure {
        if (!(runnerClass in kestrelSettings.isolatedClasses)) {
            throw new JobScheduleFailure("Runner class '${runnerClass}' is not configured " +
                "(configured: ${kestrelSettings.isolatedClasses.join(', ')})")
        }
        long now = System.currentTimeMillis()
        Map cached = templates.get(runnerClass)
        if (cached && now - (cached.at as long) < TEMPLATE_CACHE_MS) {
            return cached.template as JsonNode
        }
        String name = kestrelSettings.isolatedPodTemplatePrefix + runnerClass
        JsonNode pt
        try {
            pt = api().get("/api/v1/namespaces/${kestrelSettings.namespace}/podtemplates/${name}")
        } catch (IOException e) {
            throw new JobScheduleFailure("Cannot read PodTemplate ${name}: ${e.message}", e)
        }
        if (pt == null) {
            throw new JobScheduleFailure("PodTemplate ${name} of runner class '${runnerClass}' does not exist")
        }
        templates.put(runnerClass, [at: now, template: pt])
        pt
    }

    /**
     * Starts a run-now execution of an isolated job in its own Kubernetes Job, instead of the
     * local engine. Called with the data map upstream hands to the engine.
     *
     * @param se   the job (isolated)
     * @param data upstream's job data map: executionId, secureOpts, secureOptsExposed
     * @throws JobScheduleFailure if the Job cannot be created; upstream then deletes the execution
     */
    void dispatch(ScheduledExecution se, Map data) throws JobScheduleFailure {
        String cls = runnerClass(se)
        long id = data.executionId as long
        JsonNode pt = podTemplate(cls)
        String name = IsolatedRunTemplate.runJobName(id, suffix())
        String owner = ServerIdentity.uuidFor(kestrelSettings.namespace, name)

        // Queued, and owned by the Job's pods, before the Job can exist: a restart of this web
        // pod does not mark it incomplete, and the orphan sweeper follows the Job.
        Execution e = Execution.get(id)
        e.status = ExecutionService.EXECUTION_QUEUED
        e.serverNodeUUID = owner
        if (!e.save(flush: true)) {
            throw new JobScheduleFailure("Cannot queue execution ${id}: ${e.errors.allErrors*.defaultMessage}")
        }

        Map secure = [secureOpts: data.secureOpts, secureOptsExposed: data.secureOptsExposed].findAll { it.value }
        String ns = kestrelSettings.namespace
        ObjectNode job = template().renderRun(pt, cls, name, se.uuid, id, secure ? name : null)
        JsonNode created
        try {
            created = api().create("/apis/batch/v1/namespaces/${ns}/jobs", job)
        } catch (IOException ex) {
            throw new JobScheduleFailure("Cannot create Kubernetes Job ${name}: ${ex.message}", ex)
        }
        if (secure) {
            try {
                api().create("/api/v1/namespaces/${ns}/secrets", template().renderSecret(created, JSON.writeValueAsString(secure)))
            } catch (IOException ex) {
                try {
                    api().delete("/apis/batch/v1/namespaces/${ns}/jobs/${name}")
                } catch (IOException ignored) {
                    log.warn("Kestrel: could not delete Job ${name} after its Secret failed: ${ignored.message}")
                }
                throw new JobScheduleFailure("Cannot create the secure-option Secret for Job ${name}: ${ex.message}", ex)
            }
        }
        log.info("Kestrel: execution ${id} of ${se.project}/${se.generateFullName()} queued in Job ${name} " +
            "(class ${cls}, server ${owner}${secure ? ', with secure options' : ''})")
    }

    /**
     * Leader: the CronJobs of scheduled isolated jobs.
     *
     * @param jobs [uuid, runnerClass, bucket] of each enabled, scheduled isolated job
     * @param unsupported receives job -> reason for jobs that cannot get a CronJob
     * @return the rendered CronJobs
     */
    List<ObjectNode> cronJobs(List<Map> jobs, Map<String, String> unsupported) {
        def t = template()
        List<ObjectNode> out = []
        jobs.each { Map j ->
            try {
                out << t.renderCronJob(podTemplate(j.runnerClass as String), j.runnerClass as String,
                    j.bucket as ScheduleBucket, j.uuid as String)
            } catch (JobScheduleFailure | IllegalArgumentException e) {
                unsupported[j.uuid as String] = e.message
            }
        }
        out
    }

    /**
     * Isolated Jobs that still stand for a running server: not finished, so their pod runs, is
     * starting, or is about to be retried.
     *
     * @return server UUIDs of unfinished isolated Jobs
     */
    Set<String> liveJobServers() {
        String ns = kestrelSettings.namespace
        def list = api().list("/apis/batch/v1/namespaces/${ns}/jobs?labelSelector=${IsolatedRunTemplate.jobSelector()}")
        list.path('items').findAll { !IsolatedRunTemplate.finished(it) }
            .collect { ServerIdentity.uuidFor(ns, it.path('metadata').path('name').asText()) } as Set
    }

    private String suffix() {
        String alphabet = 'bcdfghjklmnpqrstvwxz2456789'
        (1..5).collect { alphabet.charAt(random.nextInt(alphabet.length())) }.join('')
    }
}
