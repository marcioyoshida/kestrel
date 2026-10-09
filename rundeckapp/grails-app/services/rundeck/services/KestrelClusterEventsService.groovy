package rundeck.services

import grails.events.bus.EventBusAware
import groovy.util.logging.Slf4j
import org.rundeck.kestrel.app.KestrelSettings
import org.springframework.beans.factory.InitializingBean
import rundeck.Execution

/**
 * Kestrel's answers to the {@code cluster.*} events upstream Rundeck publishes for work owned by
 * another server (OSS ships no listener; Enterprise does). In kubernetes mode an execution may
 * run on any web or runner pod.
 *
 * <p>Registered as a closure subscription, not with {@code @Subscriber}: grails-events only
 * passes a closure subscriber's return value to {@code sendAndReceive}'s reply callback, and
 * upstream waits 30 s for that reply.
 */
@Slf4j
class KestrelClusterEventsService implements InitializingBean, EventBusAware {
    static transactional = false

    KestrelSettings kestrelSettings

    @Override
    void afterPropertiesSet() {
        if (kestrelSettings?.kubernetesMode) {
            eventBus.subscribe('cluster.abortExecution') { Map data -> abortOnOwningPod(data) }
            log.info('Kestrel: listening for cluster.abortExecution')
        }
    }

    /**
     * Abort of an execution owned by another pod: record the request as {@code abortedby}; the
     * owning pod's abort poller interrupts it within ~2 seconds and the execution ends aborted.
     *
     * @param data executionId, user, killAsUser, uuidTarget (owning server)
     * @return the abort state merged into {@code ExecutionService.abortExecutionDirect}'s result
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
        log.info("Abort of execution ${id} requested by ${by} for server ${data.uuidTarget}: ${pending ? 'pending' : 'not running'}")
        pending ?
            [abortstate: ExecutionService.ABORT_PENDING, reason: null] :
            [abortstate: ExecutionService.ABORT_FAILED, reason: 'Execution is not running']
    }
}
