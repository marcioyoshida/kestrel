package org.rundeck.kestrel.scheduler

import spock.lang.Specification

import java.time.ZoneId

class CronJobReconcilerSpec extends Specification {
    static final String CJ = '/apis/batch/v1/namespaces/kestrel/cronjobs'
    def api = new FakeKubeApi()
    def settings = new CronJobTemplate.Settings('ecr/kestrel-trigger:abc', 'kestrel-trigger',
        'https://sqs.us-east-1.amazonaws.com/1/kestrel-fires.fifo', 'us-east-1', 600, [:])
    def reconciler = new CronJobReconciler(api, 'kestrel', new CronJobTemplate(settings))

    static ScheduleBucket b(String cron, String tz = 'UTC') {
        ScheduleBucket.of(cron, tz, ZoneId.of('UTC'))
    }

    def "creates one CronJob per distinct schedule, then is a no-op"() {
        when:
        def first = reconciler.reconcile([b('0 */5 * ? * *'), b('0 0 12 * * ?'), b('0 0 12 * * ?')] as Set)
        def second = reconciler.reconcile([b('0 */5 * ? * *'), b('0 0 12 * * ?')])

        then:
        first.created == 2
        !second.changed()
        second.unchanged == 2
    }

    def "time zone is part of the bucket"() {
        expect:
        b('0 0 12 * * ?', 'UTC').name != b('0 0 12 * * ?', 'America/Sao_Paulo').name
        b('0 0 12 * * ?', 'America/Sao_Paulo').timeZone == 'America/Sao_Paulo'
    }

    def "deletes CronJobs no job uses, and never touches unmanaged ones"() {
        given:
        reconciler.reconcile([b('0 */5 * ? * *'), b('0 0 12 * * ?')])
        def foreign = FakeKubeApi.JSON.createObjectNode()
        foreign.putObject('metadata').put('name', 'backup-nightly')
        api.create(CJ, foreign)

        when:
        def r = reconciler.reconcile([b('0 0 12 * * ?')])

        then:
        r.deleted == 1
        api.objects.keySet() == [CJ + '/' + b('0 0 12 * * ?').name, CJ + '/backup-nightly'] as Set
    }

    def "a changed rendering (new trigger image) replaces the CronJob"() {
        given:
        reconciler.reconcile([b('0 0 12 * * ?')])
        def newer = new CronJobReconciler(api, 'kestrel', new CronJobTemplate(new CronJobTemplate.Settings(
            'ecr/kestrel-trigger:def', 'kestrel-trigger', settings.queueUrl, 'us-east-1', 600, [:])))

        when:
        def r = newer.reconcile([b('0 0 12 * * ?')])

        then:
        r.updated == 1
        api.objects.values().first().path('spec').path('jobTemplate').path('spec').path('template')
            .path('spec').path('containers').get(0).path('image').asText() == 'ecr/kestrel-trigger:def'
    }

    def "rendered CronJob carries schedule, zone, and the scheduled-time plumbing"() {
        when:
        def cj = new CronJobTemplate(settings).render(b('0 30 2 ? * MON-FRI', 'Europe/Lisbon'))
        def c = cj.at('/spec/jobTemplate/spec/template/spec/containers/0')

        then:
        cj.at('/spec/schedule').asText() == '30 2 * * 1-5'
        cj.at('/spec/timeZone').asText() == 'Europe/Lisbon'
        cj.at('/spec/concurrencyPolicy').asText() == 'Allow'
        cj.at('/metadata/labels/kestrel.io~1managed-by').asText() == 'kestrel'
        c.path('env').find { it.path('name').asText() == 'KESTREL_JOB_NAME' }
            .at('/valueFrom/fieldRef/fieldPath').asText() == "metadata.labels['batch.kubernetes.io/job-name']"
        cj.at('/spec/jobTemplate/spec/template/spec/automountServiceAccountToken').asBoolean() == false
    }
}
