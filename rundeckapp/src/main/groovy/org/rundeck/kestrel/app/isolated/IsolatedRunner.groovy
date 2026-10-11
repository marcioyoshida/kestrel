package org.rundeck.kestrel.app.isolated

import com.fasterxml.jackson.databind.ObjectMapper
import groovy.util.logging.Slf4j
import org.quartz.Scheduler
import org.quartz.impl.matchers.GroupMatcher
import org.rundeck.app.authorization.BaseAuthContextProvider
import org.rundeck.kestrel.app.GormJobCatalog
import org.rundeck.kestrel.app.KestrelSettings
import org.rundeck.kestrel.app.QuartzLauncher
import org.rundeck.kestrel.scheduler.AwsClients
import org.rundeck.kestrel.scheduler.FireMessage
import org.rundeck.kestrel.scheduler.FiringCoordinator
import org.rundeck.kestrel.scheduler.IsolatedRunTemplate
import rundeck.Execution
import rundeck.ScheduledExecution
import rundeck.quartzjobs.ExecutionJob
import rundeck.services.ExecutionService
import rundeck.services.FrameworkService
import rundeck.services.JobSchedulerService
import rundeck.services.LogFileStorageService
import rundeck.services.ScheduledExecutionService

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The work of an isolated pod (ADR 0006), started once the application is ready: run the one
 * firing ({@code KESTREL_ISOLATED_JOB}) or execution ({@code KESTREL_ISOLATED_EXECUTION}) its
 * Kubernetes Job stands for, wait until the engine is idle and the log is stored, then exit.
 *
 * <p>Exit codes: 0 when the run succeeded or there was nothing to do; 3
 * ({@link IsolatedRunTemplate#EXIT_EXECUTION_FAILED}) when it failed, was aborted, or cannot run
 * (the Job fails without a retry); anything else (an unexpected error) lets the Job retry the pod.
 */
@Slf4j
class IsolatedRunner implements Runnable {
    static final Duration DRAIN_POLL = Duration.ofSeconds(2)
    static final Duration LOG_STORAGE_WAIT = Duration.ofMinutes(2)

    KestrelSettings kestrelSettings
    GormJobCatalog kestrelJobCatalog
    QuartzLauncher kestrelLauncher
    FrameworkService frameworkService
    ScheduledExecutionService scheduledExecutionService
    JobSchedulerService jobSchedulerService
    ExecutionService executionService
    LogFileStorageService logFileStorageService
    BaseAuthContextProvider rundeckAuthContextProvider
    Scheduler quartzScheduler
    Map<String, String> env = System.getenv()
    /** Exits the JVM; replaced in tests. */
    Closure<Void> exit = { int code -> System.exit(code) }

    @Override
    void run() {
        int code
        try {
            code = runOnce()
        } catch (Throwable t) {
            log.error("Kestrel isolated: ${t}", t)
            code = 1
        }
        log.info("Kestrel isolated: exiting with code ${code}")
        exit(code)
    }

    int runOnce() {
        String execution = env[IsolatedRunTemplate.ENV_EXECUTION]
        String job = env[IsolatedRunTemplate.ENV_JOB]
        if (execution) {
            return runExecution(execution as long)
        }
        if (job) {
            return runFiring(job, env[IsolatedRunTemplate.ENV_CRONJOB])
        }
        log.error("Kestrel isolated: neither ${IsolatedRunTemplate.ENV_EXECUTION} nor ${IsolatedRunTemplate.ENV_JOB} is set")
        IsolatedRunTemplate.EXIT_EXECUTION_FAILED
    }

    /** A run-now execution the web pod queued for this Job. */
    int runExecution(long id) {
        String server = frameworkService.serverUUID
        Execution e = Execution.withNewSession { Execution.get(id) } as Execution
        if (!e) {
            log.error("Kestrel isolated: execution ${id} does not exist")
            return IsolatedRunTemplate.EXIT_EXECUTION_FAILED
        }
        if (e.serverNodeUUID != server) {
            log.error("Kestrel isolated: execution ${id} belongs to server ${e.serverNodeUUID}, not this Job's ${server}")
            return IsolatedRunTemplate.EXIT_EXECUTION_FAILED
        }
        if (e.dateCompleted) {
            // e.g. aborted while queued: upstream ends a queued execution at once
            log.info("Kestrel isolated: execution ${id} already ended ${e.executionState}; nothing to run")
            return e.executionState == ExecutionService.EXECUTION_SUCCEEDED ? 0 : IsolatedRunTemplate.EXIT_EXECUTION_FAILED
        }
        if (e.status != ExecutionService.EXECUTION_QUEUED) {
            // An earlier pod of this Job started it and stopped. Upstream's boot cleanup (same
            // server UUID) marks it incomplete; never run it twice.
            return finishLeftover(id)
        }
        if (e.abortedby) {
            log.info("Kestrel isolated: execution ${id} was aborted by ${e.abortedby} before it started")
            Execution.withNewSession {
                executionService.cleanupRunningJobs([Execution.get(id)], ExecutionService.EXECUTION_ABORTED)
            }
            return IsolatedRunTemplate.EXIT_EXECUTION_FAILED
        }

        Map secure = readSecureOptions()
        long queuedMs = 0
        Execution.withNewTransaction {
            Execution x = Execution.get(id)
            queuedMs = System.currentTimeMillis() - (x.dateStarted?.time ?: System.currentTimeMillis())
            x.status = null
            x.dateStarted = new Date()
            x.save(flush: true, failOnError: true)
        }
        log.info("Kestrel isolated: starting execution ${id} (queued ${Math.round(queuedMs / 1000d)} s)")
        Execution.withNewSession {
            Execution x = Execution.get(id)
            ScheduledExecution se = x.scheduledExecution
            def auth = rundeckAuthContextProvider.getAuthContextForUserAndRolesAndProject(x.user, x.userRoles, x.project)
            scheduledExecutionService.scheduleTempJob(se, x.user, auth, x,
                secure.secureOpts as Map, secure.secureOptsExposed as Map, x.retryAttempt ?: 0)
            // Upstream schedules run-now "pending" and releases it when the execution is next
            // saved; here nothing saves it after this point, so release it now.
            def ident = scheduledExecutionService.getJobIdent(se, x)
            jobSchedulerService.reschedulePendingJob(ident.jobname as String, ident.groupname as String)
        }
        drain(server)
        exitCodeFor(id)
    }

    /** A CronJob firing of the isolated job {@code uuid}. */
    int runFiring(String uuid, String cronJob) {
        String jobName = kestrelSettings.podName  // the chart sets it from the Job name
        FireMessage fire = FireMessage.fromJobName(cronJob, jobName, '', '')
        String server = frameworkService.serverUUID
        FiringCoordinator.ScheduledJob job = kestrelJobCatalog.scheduledJob(uuid)
        if (job == null) {
            log.info("Kestrel isolated: job ${uuid} no longer exists; nothing to run")
            return 0
        }
        kestrelLauncher.handoffTimeout = kestrelSettings.handoffTimeout
        Long started = null
        def launcher = { FiringCoordinator.ScheduledJob j, String key, Instant at ->
            def outcome = kestrelLauncher.launch(j, key, at)
            started = outcome?.executionId
            outcome
        } as FiringCoordinator.Launcher
        def coordinator = new FiringCoordinator(kestrelJobCatalog,
            AwsClients.fireLedger(kestrelSettings.region, kestrelSettings.ledgerTable, Duration.ofDays(7)),
            launcher, server, kestrelSettings.staleClaimAfter, kestrelSettings.maxLateness, Clock.systemUTC())
        boolean settled = coordinator.fireOne(fire, job)
        if (!settled) {
            log.warn("Kestrel isolated: ${fire} ${job.name} is being handled by another server; nothing to run")
            return 0
        }
        if (started == null) {
            return 0  // skipped (late, gated, already run by an earlier pod): recorded in the ledger
        }
        drain(server)
        exitCodeFor(started)
    }

    private int finishLeftover(long id) {
        long deadline = System.currentTimeMillis() + 60_000L
        Execution e = null
        while (System.currentTimeMillis() < deadline) {
            e = Execution.withNewSession { Execution.get(id) } as Execution
            if (e.dateCompleted) {
                break
            }
            sleep(2_000)
        }
        if (!e.dateCompleted) {
            log.warn("Kestrel isolated: execution ${id} was started by an earlier pod of this Job; marking it incomplete")
            Execution.withNewSession { executionService.cleanupRunningJobs([Execution.get(id)], 'incomplete') }
        } else {
            log.info("Kestrel isolated: execution ${id} was started by an earlier pod of this Job and is ${e.executionState}; not running it again")
        }
        IsolatedRunTemplate.EXIT_EXECUTION_FAILED
    }

    private Map readSecureOptions() {
        // KESTREL_SECURE_OPTIONS_DIR: the local test harness mounts the Secret elsewhere
        Path f = Path.of(env.KESTREL_SECURE_OPTIONS_DIR ?: IsolatedRunTemplate.SECURE_OPTIONS_DIR,
            IsolatedRunTemplate.SECURE_OPTIONS_FILE)
        if (!Files.exists(f)) {
            return [:]
        }
        new ObjectMapper().readValue(f.toFile(), Map)
    }

    /**
     * Waits until the engine has nothing running or pending (a retry with a delay is a pending
     * one-shot trigger), every execution of this server has ended, and the log store has no
     * request in progress.
     */
    void drain(String server) {
        while (true) {
            boolean executing = quartzScheduler.currentlyExecutingJobs.any { it.jobDetail.jobClass == ExecutionJob }
            boolean pending = quartzScheduler.triggerGroupNames.any { g ->
                quartzScheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals(g)).any { k ->
                    quartzScheduler.getJobDetail(quartzScheduler.getTrigger(k)?.jobKey)?.jobClass == ExecutionJob
                }
            }
            long running = Execution.withNewSession {
                Execution.countByServerNodeUUIDAndDateCompletedIsNull(server)
            } as long
            if (!executing && !pending && running == 0) {
                break
            }
            sleep(DRAIN_POLL.toMillis())
        }
        long deadline = System.currentTimeMillis() + LOG_STORAGE_WAIT.toMillis()
        while (System.currentTimeMillis() < deadline) {
            Map s = logFileStorageService.storageStats
            if ((s.queuedRequestCount as long) == 0 && (s.queuedRetriesCount as long) == 0 && (s.running as long) == 0) {
                break
            }
            sleep(1_000)
        }
        sleep(3_000)  // notifications are sent asynchronously at the end of a run
    }

    /**
     * @param id the execution this pod started
     * @return 0 if it, or the last of its retries, succeeded; else 3
     */
    int exitCodeFor(long id) {
        Execution last = null
        Execution.withNewSession {
            Long next = id
            while (next != null) {
                last = Execution.get(next)
                next = last?.retryExecution?.id
            }
        }
        if (last == null) {
            return IsolatedRunTemplate.EXIT_EXECUTION_FAILED
        }
        log.info("Kestrel isolated: execution ${last.id} ended ${last.executionState}")
        last.executionState == ExecutionService.EXECUTION_SUCCEEDED ? 0 : IsolatedRunTemplate.EXIT_EXECUTION_FAILED
    }
}
