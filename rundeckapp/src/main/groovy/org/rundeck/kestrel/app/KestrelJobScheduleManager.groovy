package org.rundeck.kestrel.app

import com.dtolabs.rundeck.core.authorization.UserAndRolesAuthContext
import com.dtolabs.rundeck.core.execution.PreparedExecutionReference
import com.dtolabs.rundeck.core.jobs.JobReference
import com.dtolabs.rundeck.core.schedule.JobScheduleFailure
import com.dtolabs.rundeck.core.schedule.JobScheduleManager
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.rundeck.kestrel.app.isolated.KestrelIsolated
import rundeck.ScheduledExecution

/**
 * The upstream one-shot manager (run-now, run-at, and Kestrel firings handed to the local
 * engine), plus the hook that reports a Kestrel firing's execution id the moment the engine
 * creates it. On web pods, a run of an isolated job goes to its own Kubernetes Job instead
 * (ADR 0006). Wraps rather than extends {@code QuartzJobScheduleManagerService}: that bean's
 * GORM {@code @Listener} methods only register on an instance of its own class.
 */
@CompileStatic
class KestrelJobScheduleManager implements JobScheduleManager {
    JobScheduleManager quartzManager
    HandoffRegistry kestrelHandoffRegistry
    KestrelSettings kestrelSettings
    KestrelIsolated kestrelIsolated

    @Override
    void deleteJobSchedule(String quartzJobName, String quartzJobGroup) {
        quartzManager.deleteJobSchedule(quartzJobName, quartzJobGroup)
    }

    @Override
    Date scheduleJob(String quartzJobName, String quartzJobGroup, Map data, Date atTime, boolean pending)
        throws JobScheduleFailure
    {
        if (isolatedJob(data)) {
            // Run-later would run on this web pod, with its service account: refuse rather than
            // quietly drop the isolation (ADR 0006).
            throw new JobScheduleFailure('This job runs in its own Kubernetes pod; scheduling a single run for ' +
                'later is not supported yet. Run it now, or give it a schedule.')
        }
        quartzManager.scheduleJob(quartzJobName, quartzJobGroup, data, atTime, pending)
    }

    @Override
    boolean scheduleJobNow(String quartzJobName, String quartzJobGroup, Map data, boolean pending) throws JobScheduleFailure {
        ScheduledExecution se = isolatedJob(data)
        if (se) {
            kestrelIsolated.dispatch(se, data)
            return true
        }
        quartzManager.scheduleJobNow(quartzJobName, quartzJobGroup, data, pending)
    }

    /**
     * @return the job, if {@code data} is a run of an isolated job that this pod must hand to
     *         Kubernetes: web pods only; runner and isolated pods run what they are given
     */
    @CompileDynamic
    private ScheduledExecution isolatedJob(Map data) {
        if (!kestrelSettings?.isolatedEnabled || kestrelSettings.role != 'web' || !data?.executionId) {
            return null
        }
        def uuid = data.scheduledExecutionId
        ScheduledExecution se = uuid ? ScheduledExecution.findByUuid(uuid.toString()) : null
        KestrelIsolated.runnerClass(se) ? se : null
    }

    @Override
    Date reschedulePendingJob(String quartzJobName, String quartzJobGroup) throws JobScheduleFailure {
        quartzManager.reschedulePendingJob(quartzJobName, quartzJobGroup)
    }

    @Override
    boolean updateScheduleOwner(JobReference data) {
        quartzManager.updateScheduleOwner(data)
    }

    @Override
    String determineExecNode(JobReference job) {
        quartzManager.determineExecNode(job)
    }

    @Override
    boolean tryAcquireExecCleanerJob(String uuid, String project) {
        quartzManager.tryAcquireExecCleanerJob(uuid, project)
    }

    @Override
    boolean scheduleRemoteJob(Map data) {
        quartzManager.scheduleRemoteJob(data)
    }

    @Override
    JobScheduleManager.BeforeExecutionBehavior beforeExecution(
        PreparedExecutionReference execution, Map<String, Object> dataMap, UserAndRolesAuthContext authContext)
    {
        def key = dataMap?.get(HandoffRegistry.FIRE_KEY)
        if (key && execution?.id) {
            kestrelHandoffRegistry.started(key.toString(), Long.parseLong(execution.id))
        }
        quartzManager.beforeExecution(execution, dataMap, authContext)
    }

    @Override
    void afterExecution(PreparedExecutionReference execution, Map<String, Object> dataMap, UserAndRolesAuthContext authContext) {
        quartzManager.afterExecution(execution, dataMap, authContext)
    }
}
