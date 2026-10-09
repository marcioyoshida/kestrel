package org.rundeck.kestrel.app

import com.dtolabs.rundeck.core.authorization.UserAndRolesAuthContext
import com.dtolabs.rundeck.core.schedule.JobScheduleManager
import groovy.transform.CompileStatic
import com.dtolabs.rundeck.core.execution.PreparedExecutionReference
import rundeck.services.QuartzJobScheduleManagerService

/**
 * The upstream one-shot manager (run-now, run-at, and Kestrel firings handed to the local
 * engine), plus the hook that reports a Kestrel firing's execution id the moment the engine
 * creates it.
 */
@CompileStatic
class KestrelJobScheduleManager extends QuartzJobScheduleManagerService {
    HandoffRegistry kestrelHandoffRegistry

    @Override
    JobScheduleManager.BeforeExecutionBehavior beforeExecution(
        PreparedExecutionReference execution, Map<String, Object> dataMap, UserAndRolesAuthContext authContext)
    {
        def key = dataMap?.get(HandoffRegistry.FIRE_KEY)
        if (key && execution?.id) {
            kestrelHandoffRegistry.started(key.toString(), Long.parseLong(execution.id))
        }
        super.beforeExecution(execution, dataMap, authContext)
    }
}
