package org.rundeck.kestrel.app

import com.dtolabs.rundeck.core.authorization.UserAndRolesAuthContext
import com.dtolabs.rundeck.core.execution.PreparedExecutionReference
import com.dtolabs.rundeck.core.jobs.JobReference
import com.dtolabs.rundeck.core.schedule.JobScheduleFailure
import com.dtolabs.rundeck.core.schedule.JobScheduleManager
import groovy.transform.CompileStatic

/**
 * The upstream one-shot manager (run-now, run-at, and Kestrel firings handed to the local
 * engine), plus the hook that reports a Kestrel firing's execution id the moment the engine
 * creates it. Wraps rather than extends {@code QuartzJobScheduleManagerService}: that bean's
 * GORM {@code @Listener} methods only register on an instance of its own class.
 */
@CompileStatic
class KestrelJobScheduleManager implements JobScheduleManager {
    JobScheduleManager quartzManager
    HandoffRegistry kestrelHandoffRegistry

    @Override
    void deleteJobSchedule(String quartzJobName, String quartzJobGroup) {
        quartzManager.deleteJobSchedule(quartzJobName, quartzJobGroup)
    }

    @Override
    Date scheduleJob(String quartzJobName, String quartzJobGroup, Map data, Date atTime, boolean pending)
        throws JobScheduleFailure
    {
        quartzManager.scheduleJob(quartzJobName, quartzJobGroup, data, atTime, pending)
    }

    @Override
    boolean scheduleJobNow(String quartzJobName, String quartzJobGroup, Map data, boolean pending) throws JobScheduleFailure {
        quartzManager.scheduleJobNow(quartzJobName, quartzJobGroup, data, pending)
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
