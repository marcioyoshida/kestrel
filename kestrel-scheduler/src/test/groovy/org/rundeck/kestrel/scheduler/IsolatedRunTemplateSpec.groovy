package org.rundeck.kestrel.scheduler

import com.fasterxml.jackson.databind.node.ObjectNode
import spock.lang.Specification

import java.time.ZoneId

class IsolatedRunTemplateSpec extends Specification {
    static final String CJ = '/apis/batch/v1/namespaces/kestrel/cronjobs'
    def template = new IsolatedRunTemplate('kestrel', 600)

    static ObjectNode podTemplate(String image = 'ecr/kestrel:abc', Map annotations = [:]) {
        FakeKubeApi.JSON.valueToTree([
            apiVersion: 'v1', kind: 'PodTemplate',
            metadata  : [name: 'kestrel-isolated-default', annotations: annotations],
            template  : [
                metadata: [labels: ['app.kubernetes.io/name': 'kestrel']],
                spec    : [
                    serviceAccountName: 'kestrel-isolated',
                    restartPolicy     : 'Always',
                    containers        : [[name: 'rundeck', image: image,
                                          env : [[name: 'KESTREL_ROLE', value: 'isolated']]]],
                ],
            ],
        ]) as ObjectNode
    }

    static ScheduleBucket b(String cron) {
        ScheduleBucket.of(cron, 'UTC', ZoneId.of('UTC'))
    }

    def "a run-now Job runs one execution from the class's pod template"() {
        when:
        def job = template.renderRun(podTemplate(), 'default', 'kestrel-run-42-x1y2z', 'job-1', 42, null)
        def pod = job.at('/spec/template')
        def env = pod.at('/spec/containers/0/env').collectEntries { [it.path('name').asText(), it.path('value').asText()] }

        then:
        job.at('/metadata/name').asText() == 'kestrel-run-42-x1y2z'
        job.at('/metadata/labels/app.kubernetes.io~1component').asText() == 'isolated'
        job.at('/metadata/labels/kestrel.io~1execution').asText() == '42'
        pod.at('/metadata/labels/kestrel.io~1job').asText() == 'job-1'
        pod.at('/metadata/labels/app.kubernetes.io~1name').asText() == 'kestrel'
        pod.at('/spec/restartPolicy').asText() == 'Never'
        pod.at('/spec/serviceAccountName').asText() == 'kestrel-isolated'
        env == [KESTREL_ROLE: 'isolated', KESTREL_ISOLATED_EXECUTION: '42']
        job.at('/spec/backoffLimit').asInt() == 2
        job.at('/spec/activeDeadlineSeconds').asLong() == 86400
        job.at('/spec/podFailurePolicy/rules/0/onExitCodes/values/0').asInt() == IsolatedRunTemplate.EXIT_EXECUTION_FAILED
        job.at('/spec/podFailurePolicy/rules/1/onPodConditions/0/type').asText() == 'DisruptionTarget'
        !pod.at('/spec').has('volumes')
    }

    def "secure options travel in a Secret the Job owns, mounted read-only"() {
        given:
        def job = template.renderRun(podTemplate(), 'default', 'kestrel-run-42-x1y2z', 'job-1', 42, 'kestrel-run-42-x1y2z')
        ((ObjectNode) job.get('metadata')).put('uid', 'uid-123')

        when:
        def secret = template.renderSecret(job, '{"secureOpts":{"pw":"s3cret"}}')
        def mount = job.at('/spec/template/spec/containers/0/volumeMounts/0')

        then:
        job.at('/spec/template/spec/volumes/0/secret/secretName').asText() == 'kestrel-run-42-x1y2z'
        mount.path('mountPath').asText() == '/run/kestrel'
        mount.path('readOnly').asBoolean()
        secret.at('/metadata/ownerReferences/0/uid').asText() == 'uid-123'
        secret.at('/metadata/ownerReferences/0/kind').asText() == 'Job'
        new String(Base64.decoder.decode(secret.at('/data/secure-options.json').asText())) == '{"secureOpts":{"pw":"s3cret"}}'
    }

    def "an isolated job's CronJob runs the job itself, and the class sets the deadline"() {
        when:
        def cj = template.renderCronJob(podTemplate('ecr/kestrel:abc', ['kestrel.io/active-deadline-seconds': '900']),
            'heavy', b('0 30 2 ? * MON-FRI'), 'job-1')
        def env = cj.at('/spec/jobTemplate/spec/template/spec/containers/0/env')
            .collectEntries { [it.path('name').asText(), it.path('value').asText()] }

        then:
        cj.at('/metadata/name').asText() == IsolatedRunTemplate.cronJobName('job-1')
        cj.at('/metadata/name').asText().length() <= 52
        cj.at('/metadata/labels/kestrel.io~1managed-by').asText() == 'kestrel'
        cj.at('/metadata/labels/kestrel.io~1runner-class').asText() == 'heavy'
        !cj.at('/spec/jobTemplate/metadata/labels').has('kestrel.io/managed-by')
        cj.at('/spec/schedule').asText() == '30 2 * * 1-5'
        cj.at('/spec/jobTemplate/spec/activeDeadlineSeconds').asLong() == 900
        env.KESTREL_ISOLATED_JOB == 'job-1'
        env.KESTREL_ISOLATED_CRONJOB == cj.at('/metadata/name').asText()
        cj.at('/metadata/annotations/kestrel.io~1spec-hash').asText().length() == 16
    }

    def "the reconciler manages bucket and isolated CronJobs together; a new image replaces the isolated one"() {
        given:
        def api = new FakeKubeApi()
        def reconciler = new CronJobReconciler(api, 'kestrel', new CronJobTemplate(new CronJobTemplate.Settings(
            'ecr/kestrel-trigger:abc', 'kestrel-trigger', 'https://sqs.us-east-1.amazonaws.com/1/f.fifo', 'us-east-1', 600, [:])))
        def iso = { String image -> template.renderCronJob(podTemplate(image), 'default', b('0 0 3 * * ?'), 'job-1') }

        when:
        def first = reconciler.reconcile([b('0 */5 * ? * *')], [iso('ecr/kestrel:abc')])
        def same = reconciler.reconcile([b('0 */5 * ? * *')], [iso('ecr/kestrel:abc')])
        def upgraded = reconciler.reconcile([b('0 */5 * ? * *')], [iso('ecr/kestrel:def')])
        def bucketsOnly = reconciler.reconcile([b('0 */5 * ? * *')])

        then:
        first.created == 2
        !same.changed()
        upgraded.updated == 1 && upgraded.unchanged == 1
        bucketsOnly.deleted == 1
        api.objects.keySet() == [CJ + '/' + b('0 */5 * ? * *').name] as Set
    }

    def "a Job is finished once Complete or Failed is True"() {
        expect:
        !IsolatedRunTemplate.finished(FakeKubeApi.JSON.valueToTree([status: [active: 1]]))
        IsolatedRunTemplate.finished(FakeKubeApi.JSON.valueToTree([status: [conditions: [[type: 'Failed', status: 'True']]]]))
        IsolatedRunTemplate.finished(FakeKubeApi.JSON.valueToTree([status: [conditions: [[type: 'Complete', status: 'True']]]]))
        !IsolatedRunTemplate.finished(FakeKubeApi.JSON.valueToTree([status: [conditions: [[type: 'Suspended', status: 'True']]]]))
    }
}
