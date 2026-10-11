package org.rundeck.kestrel.app

import groovy.util.logging.Slf4j
import org.rundeck.kestrel.app.isolated.KestrelIsolated
import org.rundeck.kestrel.scheduler.FiringCoordinator
import org.rundeck.kestrel.scheduler.ScheduleBucket
import org.rundeck.kestrel.scheduler.UnsupportedScheduleException
import rundeck.Execution
import rundeck.ScheduledExecution
import rundeck.services.ExecutionService
import rundeck.services.FrameworkService
import rundeck.services.ScheduledExecutionService

import java.time.Instant
import java.time.ZoneId

/**
 * Job lookups for the scheduler, read from the Rundeck database (RDS until M2). The gating
 * mirrors what Quartz's ExecutionJob checks before a scheduled run, so a firing is never
 * claimed for a job that would not run.
 */
@Slf4j
class GormJobCatalog implements FiringCoordinator.JobCatalog {
    ScheduledExecutionService scheduledExecutionService
    ExecutionService executionService
    FrameworkService frameworkService
    KestrelSettings kestrelSettings

    /** @return time zone for jobs without one: the server's, as Quartz used */
    static ZoneId defaultZone() {
        TimeZone.getDefault().toZoneId()
    }

    /**
     * @return the bucket of a job's current schedule, or null if it has none Kubernetes can run
     */
    static ScheduleBucket bucketOf(ScheduledExecution se) {
        try {
            ScheduleBucket.of(se.generateCrontabExression(), se.timeZone, defaultZone())
        } catch (UnsupportedScheduleException e) {
            null
        }
    }

    /**
     * @return the job's runner class if it runs isolated (ADR 0006) here, else null (pool)
     */
    String isolatedClass(ScheduledExecution se) {
        kestrelSettings?.isolatedEnabled ? KestrelIsolated.runnerClass(se) : null
    }

    /**
     * Buckets every enabled, scheduled pool job needs, the isolated jobs that need their own
     * CronJob, and the jobs whose schedules cannot run.
     *
     * @return [buckets: Set<ScheduleBucket>, isolated: List<Map> ([uuid, runnerClass, bucket]),
     *          unsupported: Map<String,String> (job -> reason)]
     */
    Map desiredBuckets() {
        Set<ScheduleBucket> buckets = [] as Set
        List<Map> isolated = []
        Map<String, String> unsupported = [:]
        ScheduledExecution.withNewSession {
            ScheduledExecution.findAllByScheduledAndScheduleEnabledAndExecutionEnabled(true, true, true).each { se ->
                try {
                    def bucket = ScheduleBucket.of(se.generateCrontabExression(), se.timeZone, defaultZone())
                    String cls = isolatedClass(se)
                    if (cls) {
                        isolated << [uuid: se.uuid, runnerClass: cls, bucket: bucket]
                    } else {
                        buckets << bucket
                    }
                } catch (UnsupportedScheduleException e) {
                    unsupported[se.extid] = e.reason
                }
            }
        }
        [buckets: buckets, isolated: isolated, unsupported: unsupported]
    }

    @Override
    List<FiringCoordinator.ScheduledJob> jobsInBucket(String bucket) {
        List<FiringCoordinator.ScheduledJob> out = []
        Map<String, String> projectGate = [:]
        ScheduledExecution.withNewSession {
            boolean active = executionService.executionsAreActive
            ScheduledExecution.findAllByScheduled(true).each { se ->
                if (bucketOf(se)?.name != bucket || isolatedClass(se)) {
                    return  // isolated jobs fire from their own CronJob
                }
                out << gated(se, active, projectGate)
            }
        }
        out
    }

    /**
     * @param uuid job
     * @return the job as a firing sees it (an isolated pod's own job), or null if it is gone
     */
    FiringCoordinator.ScheduledJob scheduledJob(String uuid) {
        FiringCoordinator.ScheduledJob out = null
        ScheduledExecution.withNewSession {
            def se = ScheduledExecution.findByUuid(uuid)
            if (se) {
                out = se.scheduled ? gated(se, executionService.executionsAreActive, [:]) :
                    new FiringCoordinator.ScheduledJob(se.uuid, "${se.project}/${se.generateFullName()}", 'not scheduled')
            }
        }
        out
    }

    private FiringCoordinator.ScheduledJob gated(ScheduledExecution se, boolean active, Map<String, String> projectGate) {
        String reason = null
        if (!active) {
            reason = 'executions are disabled (passive mode)'
        } else if (!se.hasScheduleEnabled()) {
            reason = 'schedule disabled'
        } else if (!se.hasExecutionEnabled()) {
            reason = 'execution disabled'
        } else {
            reason = projectGate.computeIfAbsent(se.project) { String p -> projectReason(p) }
        }
        new FiringCoordinator.ScheduledJob(se.uuid, "${se.project}/${se.generateFullName()}", reason)
    }

    private String projectReason(String project) {
        if (frameworkService.isFrameworkProjectDisabled(project)) {
            return 'project disabled'
        }
        if (!scheduledExecutionService.isProjectExecutionEnabled(project)) {
            return 'project executions disabled'
        }
        if (!scheduledExecutionService.isProjectScheduledEnabled(project)) {
            return 'project schedules disabled'
        }
        null
    }

    @Override
    boolean scheduledExecutionExists(String jobUuid, String serverUuid, Instant since) {
        Execution.withNewSession {
            Execution.createCriteria().count {
                eq('jobUuid', jobUuid)
                eq('serverNodeUUID', serverUuid)
                eq('executionType', 'scheduled')
                ge('dateStarted', Date.from(since))
            } > 0
        }
    }
}
