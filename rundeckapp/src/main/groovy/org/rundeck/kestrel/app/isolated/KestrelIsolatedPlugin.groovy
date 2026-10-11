package org.rundeck.kestrel.app.isolated

import com.dtolabs.rundeck.core.Constants
import com.dtolabs.rundeck.core.jobs.ExecutionLifecycleStatus
import com.dtolabs.rundeck.core.jobs.JobExecutionEvent
import com.dtolabs.rundeck.core.plugins.Plugin
import com.dtolabs.rundeck.core.plugins.configuration.Describable
import com.dtolabs.rundeck.core.plugins.configuration.Description
import com.dtolabs.rundeck.plugins.ServiceNameConstants
import com.dtolabs.rundeck.plugins.jobs.ExecutionLifecyclePlugin
import com.dtolabs.rundeck.plugins.util.DescriptionBuilder
import com.dtolabs.rundeck.plugins.util.PropertyBuilder
import groovy.transform.CompileStatic
import org.rundeck.kestrel.app.KestrelSettings

/**
 * Marks a job as isolated (ADR 0006): each run gets its own Kubernetes Job, built from the chosen
 * runner class (service account, node selector, resources). It lives in the job definition as
 * <pre>
 * plugins:
 *   ExecutionLifecycle:
 *     kestrel-isolated:
 *       runnerClass: default
 * </pre>
 * so it round-trips through export, import and GitHub sync. Kestrel reads the setting when it
 * schedules and dispatches the job; at run time the plugin only records in the execution log
 * where the run happened.
 */
@Plugin(name = KestrelIsolatedPlugin.PROVIDER, service = ServiceNameConstants.ExecutionLifecycle)
@CompileStatic
class KestrelIsolatedPlugin implements ExecutionLifecyclePlugin, Describable {
    static final String PROVIDER = 'kestrel-isolated'
    static final String PROPERTY = 'runnerClass'

    KestrelSettings kestrelSettings

    @Override
    Description getDescription() {
        List<String> classes = kestrelSettings?.isolatedClasses ?: ['default']
        DescriptionBuilder.builder()
            .name(PROVIDER)
            .title('Run in its own Kubernetes pod')
            .description('Each run of this job starts a Kubernetes Job from the chosen runner class, with that ' +
                "class's service account (IAM role), node selector and resources, instead of running on the " +
                'shared runner pool. Starting the pod adds a minute or two before the job begins.')
            .property(PropertyBuilder.builder()
                .select(PROPERTY)
                .title('Runner class')
                .description('Runner classes are configured in the Helm chart (isolated.classes).')
                .values(classes)
                .defaultValue(classes[0])
                .required(true)
                .build())
            .build()
    }

    @Override
    ExecutionLifecycleStatus beforeJobStarts(JobExecutionEvent event) {
        def log = event.executionLogger
        if (kestrelSettings?.isolated) {
            log.log(Constants.INFO_LEVEL, "Kestrel: running in isolated pod ${kestrelSettings.podName}")
        } else if (kestrelSettings?.isolatedEnabled) {
            // A job reference step, or a run-later: the run happens inside the caller's pod.
            log.log(Constants.WARN_LEVEL, "Kestrel: this run is not isolated; it runs in ${kestrelSettings.role} " +
                "pod ${kestrelSettings.podName} (job references and scheduled run-later executions run in place)")
        }
        null
    }
}
