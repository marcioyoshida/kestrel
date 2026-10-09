package org.rundeck.kestrel.app

import groovy.util.logging.Slf4j
import org.quartz.JobKey
import org.quartz.Scheduler
import org.rundeck.kestrel.scheduler.FiringCoordinator
import rundeck.ScheduledExecution
import rundeck.services.ScheduledExecutionService

import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs a firing on this runner exactly as a Quartz cron trigger would have: the job's
 * JobDataMap (stored user and roles, {@code bySchedule}) handed to the upstream
 * {@code ExecutionJob} through a one-shot local trigger. Quartz here is only the in-JVM
 * harness that hosts the workflow engine (and its interrupt-based abort); it schedules
 * nothing. The headless engine replaces it in M3.
 */
@Slf4j
class QuartzLauncher implements FiringCoordinator.Launcher {
    Scheduler quartzScheduler
    ScheduledExecutionService scheduledExecutionService
    KestrelJobScheduleManager rundeckJobScheduleManager
    HandoffRegistry kestrelHandoffRegistry
    Duration handoffTimeout = Duration.ofSeconds(60)

    @Override
    FiringCoordinator.Outcome launch(FiringCoordinator.ScheduledJob job, String fireKey, Instant scheduledAt) {
        Map data = null
        String group = null
        ScheduledExecution.withNewSession {
            def se = ScheduledExecution.findByUuid(job.uuid)
            if (se) {
                data = scheduledExecutionService.createJobDetailMap(se)
                group = se.generateJobGroupName()
            }
        }
        if (data == null) {
            return FiringCoordinator.Outcome.skipped('job no longer exists')
        }
        // No serverUUID: schedule ownership does not exist in Kestrel, any runner may run any job.
        data.remove('serverUUID')
        data.put('bySchedule', true)
        data.put(HandoffRegistry.FIRE_KEY, fireKey)
        data.put('kestrel.scheduledAt', scheduledAt.toString())
        String name = 'kestrel:' + fireKey
        def outcome = kestrelHandoffRegistry.register(fireKey)
        rundeckJobScheduleManager.scheduleJobNow(name, group, data, false)
        try {
            return outcome.get(handoffTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (TimeoutException e) {
            // Not started yet (all engine threads busy). Withdraw it so it can never run late
            // after another runner has taken the claim over; if it is already executing, it
            // reports its execution id momentarily.
            def key = new JobKey(name, group)
            boolean executing = quartzScheduler.currentlyExecutingJobs.any { it.jobDetail.key == key }
            if (!executing && quartzScheduler.deleteJob(key)) {
                log.warn("Firing ${fireKey}: no engine thread within ${handoffTimeout.seconds}s; withdrawn")
                return null
            }
            return outcome.get(handoffTimeout.toMillis(), TimeUnit.MILLISECONDS)
        }
    }
}
