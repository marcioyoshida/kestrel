package org.rundeck.kestrel.app

import groovy.util.logging.Slf4j
import org.quartz.CronTrigger
import org.quartz.Scheduler
import org.quartz.impl.matchers.GroupMatcher
import org.quartz.impl.matchers.NameMatcher
import org.rundeck.kestrel.scheduler.AwsClients
import org.rundeck.kestrel.scheduler.CronJobReconciler
import org.rundeck.kestrel.scheduler.CronJobTemplate
import org.rundeck.kestrel.scheduler.FiringCoordinator
import org.rundeck.kestrel.scheduler.InClusterKubeApi
import org.rundeck.kestrel.scheduler.LeaseLeaderElector
import org.rundeck.kestrel.scheduler.SqsFireConsumer
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import rundeck.Execution
import rundeck.services.FrameworkService
import rundeck.services.ScheduledExecutionService

import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background loops of the Kubernetes scheduler, started once the application is ready
 * (after BootStrap has cleaned up this server's interrupted executions):
 * <ul>
 *   <li>web pods: Lease election; the leader reconciles CronJobs every resync interval and on demand</li>
 *   <li>runner pods: the SQS fire-queue consumer</li>
 *   <li>every pod: the cross-pod abort poller, and a check that Quartz holds no cron triggers</li>
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

    private final AtomicBoolean started = new AtomicBoolean()
    private final AtomicBoolean reconcileRequested = new AtomicBoolean()
    private final Set<Long> abortsSignalled = Collections.synchronizedSet(new HashSet<Long>())
    private ScheduledExecutorService loops
    private LeaseLeaderElector elector
    private CronJobReconciler reconciler
    private SqsFireConsumer consumer
    private volatile long lastResync

    @Override
    void onApplicationEvent(ApplicationReadyEvent event) {
        if (!kestrelSettings.kubernetesMode || !started.compareAndSet(false, true)) {
            return
        }
        kestrelSettings.validate()
        loops = Executors.newScheduledThreadPool(3, { Runnable r ->
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
            elector = new LeaseLeaderElector(api, kestrelSettings.namespace, LEASE_NAME, kestrelSettings.podName,
                kestrelSettings.leaseDuration, Clock.systemUTC())
            reconciler = new CronJobReconciler(api, kestrelSettings.namespace, new CronJobTemplate(
                new CronJobTemplate.Settings(kestrelSettings.triggerImage, kestrelSettings.triggerServiceAccount,
                    kestrelSettings.queueUrl, kestrelSettings.region, kestrelSettings.startingDeadlineSeconds,
                    kestrelSettings.triggerNodeSelector)))
            long renew = Math.max(1, (long) (kestrelSettings.leaseDuration.seconds / 3))
            loops.scheduleWithFixedDelay(this.&leaderTick, 0, renew, TimeUnit.SECONDS)
        }
        loops.scheduleWithFixedDelay(this.&abortTick, 2, 2, TimeUnit.SECONDS)
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
