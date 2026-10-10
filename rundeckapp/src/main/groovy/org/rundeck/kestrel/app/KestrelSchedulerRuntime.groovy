package org.rundeck.kestrel.app

import grails.events.bus.EventBus
import groovy.util.logging.Slf4j
import org.quartz.CronTrigger
import org.quartz.Scheduler
import org.quartz.impl.matchers.GroupMatcher
import org.quartz.impl.matchers.NameMatcher
import org.rundeck.app.acl.ACLCacheControl
import org.rundeck.app.acl.AppACLContext
import org.rundeck.kestrel.scheduler.AwsClients
import org.rundeck.kestrel.scheduler.ClusterBus
import org.rundeck.kestrel.scheduler.CronJobReconciler
import org.rundeck.kestrel.scheduler.CronJobTemplate
import org.rundeck.kestrel.scheduler.FiringCoordinator
import org.rundeck.kestrel.scheduler.InClusterKubeApi
import org.rundeck.kestrel.scheduler.KubeApi
import org.rundeck.kestrel.scheduler.LeaseLeaderElector
import org.rundeck.kestrel.scheduler.ServerIdentity
import org.rundeck.kestrel.scheduler.SqsFireConsumer
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import rundeck.Execution
import rundeck.services.ExecutionService
import rundeck.services.FrameworkService
import rundeck.services.ScheduledExecutionService

import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background loops of the Kubernetes scheduler, started once the application is ready
 * (after BootStrap has cleaned up this server's interrupted executions):
 * <ul>
 *   <li>web pods: Lease election; the leader reconciles CronJobs every resync interval and on demand</li>
 *   <li>runner pods: the SQS fire-queue consumer</li>
 *   <li>web leader: the orphan sweeper (executions left running by pods that no longer exist)</li>
 *   <li>every pod: the {@code cluster.abortExecution} listener and abort poller, the ACL cache bus
 *       ({@code cluster.clearAclCache} published and applied across pods), and a check that Quartz
 *       holds no cron triggers</li>
 * </ul>
 */
@Slf4j
class KestrelSchedulerRuntime implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {
    static final String LEASE_NAME = 'kestrel-scheduler'

    KestrelSettings kestrelSettings
    GormJobCatalog kestrelJobCatalog
    QuartzLauncher kestrelLauncher
    HandoffRegistry kestrelHandoffRegistry
    FrameworkService frameworkService
    ScheduledExecutionService scheduledExecutionService
    Scheduler quartzScheduler
    ACLCacheControl authorizationService
    def executionService

    private final AtomicBoolean started = new AtomicBoolean()
    private final AtomicBoolean reconcileRequested = new AtomicBoolean()
    private final Set<Long> abortsSignalled = Collections.synchronizedSet(new HashSet<Long>())
    private ScheduledExecutorService loops
    private LeaseLeaderElector elector
    private CronJobReconciler reconciler
    private SqsFireConsumer consumer
    private volatile long lastResync
    private volatile long lastSweep
    private KubeApi kubeApi
    private ClusterBus aclBus
    private final Map<String, Integer> orphanSightings = new ConcurrentHashMap<>()
    static final long SWEEP_INTERVAL_MS = 120_000L

    @Override
    void onApplicationEvent(ApplicationReadyEvent event) {
        if (!kestrelSettings.kubernetesMode || !started.compareAndSet(false, true)) {
            return
        }
        kestrelSettings.validate()
        loops = Executors.newScheduledThreadPool(4, { Runnable r ->
            def t = new Thread(r, 'kestrel-scheduler')
            t.daemon = true
            t
        })
        String serverUuid = frameworkService.serverUUID
        log.info("Kestrel scheduler: role=${kestrelSettings.role} pod=${kestrelSettings.podName} server=${serverUuid}")

        if (kestrelSettings.runner) {
            quartzScheduler.listenerManager.addJobListener(kestrelHandoffRegistry, NameMatcher.jobNameStartsWith('kestrel:'))
            kestrelLauncher.handoffTimeout = kestrelSettings.handoffTimeout
            def coordinator = new FiringCoordinator(kestrelJobCatalog,
                AwsClients.fireLedger(kestrelSettings.region, kestrelSettings.ledgerTable, Duration.ofDays(7)),
                kestrelLauncher, serverUuid, kestrelSettings.staleClaimAfter, kestrelSettings.maxLateness,
                Clock.systemUTC())
            consumer = AwsClients.fireConsumer(kestrelSettings.region, kestrelSettings.queueUrl, coordinator)
            def t = new Thread(consumer, 'kestrel-fire-consumer')
            t.daemon = true
            t.start()
        } else {
            def api = InClusterKubeApi.fromEnvironment()
            kubeApi = api
            elector = new LeaseLeaderElector(api, kestrelSettings.namespace, LEASE_NAME, kestrelSettings.podName,
                kestrelSettings.leaseDuration, Clock.systemUTC())
            reconciler = new CronJobReconciler(api, kestrelSettings.namespace, new CronJobTemplate(
                new CronJobTemplate.Settings(kestrelSettings.triggerImage, kestrelSettings.triggerServiceAccount,
                    kestrelSettings.queueUrl, kestrelSettings.region, kestrelSettings.startingDeadlineSeconds,
                    kestrelSettings.triggerNodeSelector)))
            long renew = Math.max(1, (long) (kestrelSettings.leaseDuration.seconds / 3))
            loops.scheduleWithFixedDelay(this.&leaderTick, 0, renew, TimeUnit.SECONDS)
        }
        // A closure subscription, not @Subscriber: grails-events passes only a closure subscriber's
        // return value to sendAndReceive's reply, and upstream waits 30 s for that reply.
        // The application's bus (EventBusAware would silently build a private one if not wired).
        try {
            EventBus bus = event.applicationContext.getBean(EventBus)
            bus.subscribe('cluster.abortExecution') { Map data -> abortOnOwningPod(data) }
            log.info("Kestrel: listening for cluster.abortExecution on ${bus.getClass().simpleName}")
        } catch (Exception e) {
            log.error("Kestrel: cannot listen for cluster.abortExecution; cross-pod abort will time out: ${e}", e)
        }
        loops.scheduleWithFixedDelay(this.&abortTick, 2, 2, TimeUnit.SECONDS)

        // ACL cache invalidation across pods: upstream publishes cluster.clearAclCache after a
        // policy changes on this pod; other pods learn of it from the bus within ~3 s.
        try {
            aclBus = AwsClients.clusterBus(kestrelSettings.region, kestrelSettings.ledgerTable, 'acl',
                kestrelSettings.podName ?: serverUuid)
            aclBus.poll()  // start from now
            EventBus bus = event.applicationContext.getBean(EventBus)
            bus.subscribe('cluster.clearAclCache') { Map data -> publishAclChange(data) }
            loops.scheduleWithFixedDelay(this.&aclTick, 3, 3, TimeUnit.SECONDS)
            log.info('Kestrel: ACL cache bus active')
        } catch (Exception e) {
            log.error("Kestrel: ACL cache bus unavailable; other pods see ACL changes only on cache refresh: ${e}", e)
        }
        loops.scheduleWithFixedDelay(this.&assertNoQuartzCron, 0, 5, TimeUnit.MINUTES)
    }

    /** Asks the leader to reconcile soon (a job's schedule changed). No-op on other pods. */
    void requestReconcile() {
        reconcileRequested.set(true)
    }

    private void leaderTick() {
        try {
            boolean leader = elector.tick()
            long now = System.currentTimeMillis()
            boolean due = now - lastResync >= kestrelSettings.resyncInterval.toMillis()
            if (leader && now - lastSweep >= SWEEP_INTERVAL_MS) {
                lastSweep = now
                try {
                    sweepOrphans()
                } catch (Throwable t) {
                    log.warn("Kestrel orphan sweep failed: ${t.message}")
                }
            }
            if (leader && (due || reconcileRequested.getAndSet(false))) {
                lastResync = now
                Map desired = kestrelJobCatalog.desiredBuckets()
                def result = reconciler.reconcile(desired.buckets as Collection)
                Map unsupported = desired.unsupported as Map
                if (result.changed() || unsupported) {
                    log.info("Kestrel reconcile: ${result}; buckets=${desired.buckets.size()}" +
                        (unsupported ? "; ${unsupported.size()} job(s) have schedules Kubernetes cannot run: ${unsupported}" : ''))
                }
            }
        } catch (Throwable t) {
            log.error("Kestrel leader tick failed: ${t}", t)
        }
    }

    /** Publishes a local ACL change for the other pods. */
    Map publishAclChange(Map data) {
        try {
            long seq = aclBus.publish([path: data.path, project: data.project, system: data.system])
            [clearCacheState: 'published', reason: "bus event ${seq}".toString()]
        } catch (Exception e) {
            log.warn("Kestrel: could not publish ACL change ${data}: ${e.message}")
            [clearCacheState: 'failed', reason: e.message]
        }
    }

    private void aclTick() {
        try {
            aclBus.poll().each { Map<String, String> e ->
                if (e._reset) {
                    log.warn("Kestrel: ACL bus ${e._reset}; cached policies refresh on their own schedule")
                    return
                }
                def ctx = e.system == 'true' || !e.project ? AppACLContext.system() : AppACLContext.project(e.project)
                authorizationService.cleanAclCache(ctx, e.path)
                log.info("Kestrel: ACL cache invalidated for ${e.project ?: 'system'}:${e.path} (changed on another pod)")
            }
        } catch (Throwable t) {
            log.warn("Kestrel ACL bus poll failed: ${t.message}")
        }
    }

    /**
     * Leader only: executions still running for a server UUID that no web or runner pod carries
     * (a StatefulSet scaled down) are marked incomplete, as upstream does at boot for its own.
     * A UUID must be absent for two consecutive sweeps, so a pod being recreated is never swept.
     */
    void sweepOrphans() {
        String ns = kestrelSettings.namespace
        def pods = kubeApi.list("/api/v1/namespaces/${ns}/pods?labelSelector=" +
            URLEncoder.encode('app.kubernetes.io/component in (web,runner)', 'UTF-8'))
        Set<String> live = pods.path('items').collect { ServerIdentity.uuidFor(ns, it.path('metadata').path('name').asText()) } as Set
        List<String> owners = []
        Execution.withNewSession {
            owners = Execution.createCriteria().list {
                isNull('dateCompleted')
                isNotNull('serverNodeUUID')
                projections { distinct('serverNodeUUID') }
            } as List<String>
        }
        Set<String> gone = owners.findAll { !live.contains(it) } as Set
        orphanSightings.keySet().retainAll(gone)
        gone.each { String uuid ->
            int n = orphanSightings.merge(uuid, 1) { a, b -> a + b }
            if (n >= 2) {
                log.warn("Kestrel: marking executions of departed server ${uuid} incomplete (no pod carries it)")
                executionService.cleanupRunningJobs(uuid, null, new Date())
                orphanSightings.remove(uuid)
            }
        }
    }

    /**
     * Upstream publishes {@code cluster.abortExecution} when the execution belongs to another
     * server (OSS ships no listener). Record the request as {@code abortedby}; the owning pod's
     * {@link #abortTick} interrupts it within ~2 seconds and the execution ends aborted.
     *
     * @param data executionId, user, killAsUser, uuidTarget (owning server)
     * @return abort state merged into {@code ExecutionService.abortExecutionDirect}'s result
     */
    Map abortOnOwningPod(Map data) {
        Long id = data.executionId as Long
        String by = (data.killAsUser ?: data.user) as String
        boolean pending = false
        Execution.withNewTransaction {
            Execution e = Execution.get(id)
            if (e && !e.dateCompleted) {
                if (!e.abortedby) {
                    e.abortedby = by
                    e.save(flush: true)
                }
                pending = true
            }
        }
        log.info("Kestrel: abort of execution ${id} by ${by} for server ${data.uuidTarget}: ${pending ? 'pending' : 'not running'}")
        pending ?
            [abortstate: ExecutionService.ABORT_PENDING, reason: null] :
            [abortstate: ExecutionService.ABORT_FAILED, reason: 'Execution is not running']
    }

    /**
     * Interrupts executions this pod runs whose abort was requested on another pod (the
     * {@code cluster.abortExecution} handler records it as {@code abortedby}).
     */
    private void abortTick() {
        try {
            List<Long> ids = []
            Execution.withNewSession {
                ids = Execution.createCriteria().list {
                    isNull('dateCompleted')
                    isNotNull('abortedby')
                    eq('serverNodeUUID', frameworkService.serverUUID)
                    projections { property('id') }
                } as List<Long>
            }
            abortsSignalled.retainAll(ids)
            ids.findAll { !abortsSignalled.contains(it) }.each { Long id ->
                def ctx = scheduledExecutionService.findExecutingQuartzJob(id)
                if (ctx) {
                    log.info("Kestrel: interrupting execution ${id} (abort requested on another pod)")
                    quartzScheduler.interrupt(ctx.fireInstanceId)
                    abortsSignalled << id
                }
            }
        } catch (Throwable t) {
            log.warn("Kestrel abort poll failed: ${t.message}")
        }
    }

    /** Kubernetes mode must never hold a Quartz cron trigger; report any that appear. */
    void assertNoQuartzCron() {
        try {
            List<String> cron = []
            quartzScheduler.triggerGroupNames.each { g ->
                quartzScheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals(g)).each { k ->
                    if (quartzScheduler.getTrigger(k) instanceof CronTrigger) {
                        cron << k.toString()
                    }
                }
            }
            if (cron) {
                log.error("Kestrel: ${cron.size()} Quartz cron trigger(s) registered in kubernetes mode: ${cron}")
            } else {
                log.info('Kestrel: Quartz cron triggers: 0')
            }
        } catch (Throwable t) {
            log.warn("Kestrel Quartz check failed: ${t.message}")
        }
    }

    @Override
    void destroy() {
        consumer?.stop()
        loops?.shutdownNow()
    }
}
