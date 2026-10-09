package org.rundeck.kestrel.app

import groovy.util.logging.Slf4j
import rundeck.ScheduledExecution
import rundeck.services.LocalJobSchedulesManager

/**
 * {@code SchedulesManager} for {@code kestrel.scheduler.mode=kubernetes}: a job's schedule is
 * never registered with Quartz. Saving a schedule asks the reconciler to converge the
 * CronJobs (it also resyncs on its own); next-run times are computed from the cron expression.
 */
@Slf4j
class KubernetesJobSchedulesManager extends LocalJobSchedulesManager {
    KestrelSchedulerRuntime kestrelSchedulerRuntime

    @Override
    Map handleScheduleDefinitions(String jobUUID, boolean isUpdate) {
        def se = ScheduledExecution.findByUuid(jobUUID)
        kestrelSchedulerRuntime?.requestReconcile()
        return [nextTime: se ? calculateNextExecutionTime(se) : null]
    }
}
