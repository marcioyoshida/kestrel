package org.rundeck.kestrel.scheduler

import org.quartz.CronExpression
import spock.lang.Specification
import spock.lang.Unroll

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * The translation must be exact: for every minute of a year, the Kubernetes schedule fires iff
 * Quartz would. A small cron matcher with robfig/cron semantics (the parser Kubernetes uses)
 * stands in for the CronJob controller.
 */
class CronTranslatorSpec extends Specification {

    @Unroll
    def "translates #quartz to #expected"() {
        expect:
        CronTranslator.toKubernetes(quartz) == expected

        where:
        quartz                       | expected
        '0 0 12 * * ?'               | '0 12 * * *'
        '0 0 12 * * ? *'             | '0 12 * * *'
        '0 */5 * ? * *'              | '*/5 * * * *'
        '0 0/15 * ? * *'             | '0-59/15 * * * *'
        '0 30 2 ? * MON-FRI'         | '30 2 * * 1-5'
        '0 30 2 ? * 2-6'             | '30 2 * * 1-5'
        '0 0 9 ? * SUN,SAT'          | '0 9 * * 0,6'
        '0 0 9 ? * 1,7'              | '0 9 * * 0,6'
        '0 0 9 ? * 2/2'              | '0 9 * * 1-6/2'
        '0 15 10 1,15 * ?'           | '15 10 1,15 * *'
        '0 0 0 1 JAN,JUL ?'          | '0 0 1 1,7 *'
        '00 5 3-23/4 */2 * ?'        | '5 3-23/4 */2 * *'
        '0 0 0 ? * *'                | '0 0 * * *'
    }

    @Unroll
    def "rejects #quartz (#why)"() {
        when:
        CronTranslator.toKubernetes(quartz)

        then:
        UnsupportedScheduleException e = thrown()
        e.reason.contains(why)

        where:
        quartz                  | why
        '30 0 12 * * ?'         | 'seconds must be 0'
        '0/10 * * * * ?'        | 'seconds must be 0'
        '0 0 12 * * ? 2027'     | 'year'
        '0 0 12 L * ?'          | 'Quartz-only'
        '0 0 12 15W * ?'        | 'Quartz-only'
        '0 0 12 LW * ?'         | 'Quartz-only'
        '0 0 12 ? * 6L'         | 'Quartz-only'
        '0 0 12 ? * 6#3'        | 'Quartz-only'
        '0 0 22-2 * * ?'        | 'wrapping'
        '0 0 12 1 * MON'        | 'cannot both be set'
        '0 0 12 * *'            | 'expected 6 or 7'
        '0 60 12 * * ?'         | 'outside'
        '0 0 12 ? FOO *'        | 'unknown month'
        ''                      | 'empty'
    }

    @Unroll
    def "#quartz fires on exactly the same minutes as Quartz for a year"() {
        given:
        String k8s = CronTranslator.toKubernetes(quartz)
        CronExpression oracle = new CronExpression(quartz)
        oracle.setTimeZone(TimeZone.getTimeZone('UTC'))
        def matcher = new RobfigMatcher(k8s)
        def t = ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        def end = t.plusYears(1)
        int mismatches = 0
        int fires = 0

        when:
        while (t.isBefore(end)) {
            boolean q = oracle.isSatisfiedBy(Date.from(t.toInstant()))
            boolean k = matcher.matches(t)
            if (q != k) mismatches++
            if (q) fires++
            t = t.plusMinutes(1)
        }

        then:
        mismatches == 0
        fires > 0

        where:
        quartz << [
            '0 0 12 * * ?', '0 */5 * ? * *', '0 0/15 * ? * *', '0 30 2 ? * MON-FRI', '0 30 2 ? * 2-6',
            '0 0 9 ? * 1,7', '0 0 9 ? * 2/2', '0 15 10 1,15 * ?', '0 0 0 1 JAN,JUL ?',
            '0 5 3-23/4 */2 * ?', '0 0 0 ? * *', '0 0 6 */3 */2 ?', '0 45 23 ? * 7',
        ]
    }

    /** Minimal 5-field matcher with robfig/cron v3 semantics (Kubernetes' parser). */
    static class RobfigMatcher {
        final List<Set<Integer>> sets = []
        final boolean domStar
        final boolean dowStar
        static final List<List<Integer>> RANGES = [[0, 59], [0, 23], [1, 31], [1, 12], [0, 6]]

        RobfigMatcher(String expr) {
            def f = expr.split(' ')
            assert f.length == 5
            f.eachWithIndex { String field, int i -> sets << parse(field, RANGES[i][0], RANGES[i][1]) }
            domStar = f[2] == '*'
            dowStar = f[4] == '*'
        }

        static Set<Integer> parse(String field, int min, int max) {
            Set<Integer> out = [] as Set
            field.split(',').each { String item ->
                def (range, step) = item.contains('/') ? item.split('/') as List : [item, '1']
                int s = step as int
                int a, b
                if (range == '*') { a = min; b = max }
                else if (range.contains('-')) { def p = range.split('-'); a = p[0] as int; b = p[1] as int }
                else { a = range as int; b = item.contains('/') ? max : a }
                for (int v = a; v <= b; v += s) out << v
            }
            out
        }

        boolean matches(ZonedDateTime t) {
            boolean dom = sets[2].contains(t.dayOfMonth)
            boolean dow = sets[4].contains(t.dayOfWeek.value % 7)
            boolean day = (domStar || dowStar) ? (dom && dow) : (dom || dow)
            sets[0].contains(t.minute) && sets[1].contains(t.hour) && sets[3].contains(t.monthValue) && day
        }
    }
}
