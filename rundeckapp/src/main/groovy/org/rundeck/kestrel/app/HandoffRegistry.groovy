package org.rundeck.kestrel.app

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException
import org.quartz.listeners.JobListenerSupport
import org.rundeck.kestrel.scheduler.FiringCoordinator

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Connects a firing handed to the local engine with what the engine did: the execution id
 * (reported through {@code JobScheduleManager.beforeExecution}) or a skip (the engine finished
 * the job without creating an execution, e.g. the project's schedules were disabled).
 */
@Slf4j
@CompileStatic
class HandoffRegistry extends JobListenerSupport {
    /** JobDataMap key carrying the fire key of a Kestrel firing. */
    static final String FIRE_KEY = 'kestrel.fireKey'

    private final Map<String, CompletableFuture<FiringCoordinator.Outcome>> pending = new ConcurrentHashMap<>()

    /**
     * @param fireKey firing
     * @return future completed with the engine's outcome
     */
    CompletableFuture<FiringCoordinator.Outcome> register(String fireKey) {
        def f = new CompletableFuture<FiringCoordinator.Outcome>()
        pending.put(fireKey, f)
        f.whenComplete { r, e -> pending.remove(fireKey, f) }
        f
    }

    /** Called when the engine created the execution. */
    void started(String fireKey, long executionId) {
        pending.get(fireKey)?.complete(FiringCoordinator.Outcome.started(executionId))
    }

    @Override
    String getName() {
        'kestrel-handoff'
    }

    @Override
    void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        def key = context.mergedJobDataMap.getString(FIRE_KEY)
        if (key) {
            pending.get(key)?.complete(FiringCoordinator.Outcome.skipped(
                'the engine did not create an execution' + (jobException ? ': ' + jobException.message : '')))
        }
    }
}
