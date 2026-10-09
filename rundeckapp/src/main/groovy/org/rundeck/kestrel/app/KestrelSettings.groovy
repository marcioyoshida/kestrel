package org.rundeck.kestrel.app

import groovy.transform.CompileStatic
import org.springframework.core.env.PropertyResolver

import java.time.Duration

/**
 * Kestrel scheduler settings (ADR 0001 §2), from Spring properties {@code kestrel.*}; environment
 * variables such as {@code KESTREL_SCHEDULER_MODE} bind to them. The Helm chart sets all of them.
 */
@CompileStatic
class KestrelSettings {
    /** {@code quartz} (upstream) or {@code kubernetes} (CronJobs + SQS + runners). */
    String mode = 'quartz'
    /** {@code web} (UI/API, reconciler leader candidate) or {@code runner} (fire-queue consumer). */
    String role = 'web'
    String namespace
    String podName
    String region
    String queueUrl
    String ledgerTable
    String triggerImage
    String triggerServiceAccount = 'kestrel-trigger'
    Map<String, String> triggerNodeSelector = [:]
    Duration resyncInterval = Duration.ofSeconds(30)
    Duration leaseDuration = Duration.ofSeconds(15)
    Duration handoffTimeout = Duration.ofSeconds(60)
    Duration staleClaimAfter = Duration.ofSeconds(180)
    Duration maxLateness = Duration.ofMinutes(15)
    int startingDeadlineSeconds = 600

    /**
     * @param config Grails/Spring configuration; each key also falls back to its environment
     *               variable form ({@code kestrel.queue-url} -> {@code KESTREL_QUEUE_URL})
     * @return settings bound from {@code kestrel.*}
     */
    static KestrelSettings from(PropertyResolver config) {
        def s = new KestrelSettings()
        s.mode = prop(config, 'kestrel.scheduler.mode', 'quartz').trim().toLowerCase()
        s.role = prop(config, 'kestrel.role', 'web').trim().toLowerCase()
        s.namespace = prop(config, 'kestrel.namespace', null)
        s.podName = prop(config, 'kestrel.pod-name', null)
        s.region = prop(config, 'kestrel.region', System.getenv('AWS_REGION') ?: 'us-east-1')
        s.queueUrl = prop(config, 'kestrel.queue-url', null)
        s.ledgerTable = prop(config, 'kestrel.ledger-table', null)
        s.triggerImage = prop(config, 'kestrel.trigger.image', null)
        s.triggerServiceAccount = prop(config, 'kestrel.trigger.service-account', 'kestrel-trigger')
        String arch = prop(config, 'kestrel.trigger.arch', null)
        if (arch) {
            s.triggerNodeSelector = ['kubernetes.io/arch': arch]
        }
        s.resyncInterval = Duration.ofSeconds(prop(config, 'kestrel.resync-seconds', '30').toLong())
        s.maxLateness = Duration.ofMinutes(prop(config, 'kestrel.max-lateness-minutes', '15').toLong())
        s
    }

    private static String prop(PropertyResolver config, String key, String dflt) {
        String v = config?.getProperty(key)
        if (!v) {
            v = System.getenv(key.toUpperCase().replace('.', '_').replace('-', '_'))
        }
        v ?: dflt
    }

    /** @return true when Kubernetes CronJobs replace Quartz cron triggers */
    boolean isKubernetesMode() {
        mode == 'kubernetes'
    }

    /** @return true on runner pods */
    boolean isRunner() {
        role == 'runner'
    }

    /**
     * @throws IllegalStateException naming every missing setting, so a misconfigured pod fails at boot
     */
    void validate() {
        if (!kubernetesMode) {
            return
        }
        def missing = [
            'kestrel.namespace': namespace, 'kestrel.pod-name': podName,
            'kestrel.queue-url': queueUrl, 'kestrel.ledger-table': ledgerTable,
        ].findAll { k, v -> !v }.keySet()
        if (!runner && !triggerImage) {
            missing += ['kestrel.trigger.image']
        }
        if (!(role in ['web', 'runner'])) {
            throw new IllegalStateException("kestrel.role must be web or runner, got '${role}'")
        }
        if (missing) {
            throw new IllegalStateException("kestrel.scheduler.mode=kubernetes requires ${missing.join(', ')}")
        }
    }
}
