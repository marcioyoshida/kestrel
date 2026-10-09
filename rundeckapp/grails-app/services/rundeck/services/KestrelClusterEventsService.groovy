package rundeck.services

import grails.events.annotation.Subscriber
import groovy.util.logging.Slf4j
import org.rundeck.kestrel.app.KestrelSettings
import rundeck.Execution

/**
 * Kestrel's answers to the {@code cluster.*} events upstream Rundeck publishes for work owned by
 * another server (OSS ships no listener; Enterprise does). In kubernetes mode an execution may
 * run on any web or runner pod.
 */
@Slf4j
class KestrelClusterEventsService {
    static transactional = false

    KestrelSettings kestrelSettings

    /**
     * Abort of an execution owned by another pod: record the request as {@code abortedby}; the
     * owning pod's abort poller interrupts it within ~2 seconds and the execution ends aborted.
     *
     * @param data executionId, user, killAsUser, uuidTarget (owning server)
     * @return the abort state for {@code ExecutionService.abortExecutionDirect}, or null when
     *         Kestrel is not in kubernetes mode
     */
    @Subscriber('cluster.abortExecution')
    Map abortOnOwningPod(Map data) {
        if (!kestrelSettings?.kubernetesMode) {
            return null
        }
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
