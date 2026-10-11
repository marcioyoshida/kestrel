package org.rundeck.kestrel.github

import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GitHubSyncSpec extends Specification {
    static final String CONFIG = '''branch: main
projects:
  - path: jobs/ops
    project: ops
  - path: jobs/ops/db
    project: db
'''
    FakeGitHub gh = new FakeGitHub().start()
    FakeTarget target = new FakeTarget()
    GitHubSync sync

    static String pem
    static java.security.PublicKey publicKey

    def setupSpec() {
        def kp = KeyPairGenerator.getInstance('RSA').with { initialize(2048); generateKeyPair() }
        publicKey = kp.public
        // PKCS#8 DER (2048-bit) = 26 header bytes + the PKCS#1 key, as GitHub hands it out
        byte[] pkcs1 = kp.private.encoded[26..-1] as byte[]
        pem = "-----BEGIN RSA PRIVATE KEY-----\n${pkcs1.encodeBase64().toString().replaceAll('(.{64})', '$1\n')}\n-----END RSA PRIVATE KEY-----\n"
    }

    def setup() {
        def client = new GitHubClient(gh.url, new GitHubAppKey('123', pem), Clock.fixed(Instant.parse('2026-10-11T12:00:00Z'), ZoneOffset.UTC))
        sync = new GitHubSync(client, target)
    }

    def cleanup() { gh.stop() }

    static String job(String uuid, String name = uuid) {
        "- uuid: ${uuid}\n  name: ${name}\n  sequence:\n    commands:\n      - exec: echo ${name}\n"
    }

    def "webhook signature: GitHub's documented example"() {
        expect:
        WebhookSignature.sign("It's a Secret to Everybody", 'Hello, World!'.bytes) ==
                'sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17'
        WebhookSignature.verify("It's a Secret to Everybody", 'Hello, World!'.bytes,
                'sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17')
        !WebhookSignature.verify("It's a Secret to Everybody", 'Hello, World?'.bytes,
                'sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17')
        !WebhookSignature.verify('', 'x'.bytes, 'sha256=00')
        !WebhookSignature.verify('s', 'x'.bytes, null)
    }

    def "app JWT is RS256 over iss/iat/exp, from a PKCS#1 key (GitHub's format)"() {
        when:
        def jwt = new GitHubAppKey('123', pem).jwt(Instant.ofEpochSecond(1000000))
        def (h, p, s) = jwt.split('\\.')
        def verifier = Signature.getInstance('SHA256withRSA')
        verifier.initVerify(publicKey)
        verifier.update("${h}.${p}".getBytes(StandardCharsets.US_ASCII))

        then:
        new String(Base64.urlDecoder.decode(p)) == '{"iat":999940,"exp":1000540,"iss":"123"}'
        verifier.verify(Base64.urlDecoder.decode(s))
    }

    def "kestrel.yaml: longest path wins, only yaml files, errors listed"() {
        when:
        def c = RepoConfig.parse(CONFIG)

        then:
        c.errors().isEmpty()
        c.branch() == 'main'
        c.deleteRemoved()
        c.projectFor('jobs/ops/a.yaml') == 'ops'
        c.projectFor('jobs/ops/db/b.yml') == 'db'
        c.projectFor('jobs/ops/readme.md') == null
        c.projectFor('jobs/opsx/a.yaml') == null
        c.projectFor('kestrel.yaml') == null

        expect:
        RepoConfig.parse('branch: [').errors()[0].startsWith('kestrel.yaml is not valid YAML')
        RepoConfig.parse('projects: []').errors() == ['projects must list at least one {path, project} mapping']
        RepoConfig.parse('onDelete: never\nprojects: [{path: a, project: b}]\nextra: 1').errors() ==
                ['onDelete must be delete or keep, not never', 'unknown keys in kestrel.yaml: [extra]']
        RepoConfig.parse('projects: [{path: ., project: all}]').projectFor('x.yaml') == 'all'
    }

    def "job files: uuids (or ids) and the line each job starts on"() {
        when:
        def p = JobFiles.parse("# jobs\n" + job('u1', 'one') + "- id: u2\n  name: two\n- name: three\n")

        then:
        p.ok()
        p.jobs()*.uuid() == ['u1', 'u2', null]
        p.jobs()*.line() == [2, 7, 9]

        expect:
        JobFiles.parse('- name: [').errorLine() == 1
        JobFiles.parse('name: x').error() == 'a job file must be a YAML list of Rundeck jobs'
    }

    def "links: github.com and GitHub Enterprise; paths encoded per segment"() {
        expect:
        new GitHubLinks(api).edit('o/r', 'release/1', 'jobs/a b.yaml') == edit
        new GitHubLinks(api).blob('o/r', 'abc', 'x.yaml') == blob

        where:
        api                             | edit                                                     | blob
        'https://api.github.com'        | 'https://github.com/o/r/edit/release/1/jobs/a%20b.yaml'  | 'https://github.com/o/r/blob/abc/x.yaml'
        'https://ghe.example/api/v3/'   | 'https://ghe.example/o/r/edit/release/1/jobs/a%20b.yaml' | 'https://ghe.example/o/r/blob/abc/x.yaml'
    }

    def "first push syncs the whole tree; then only what changed"() {
        given:
        gh.commit('main', ['kestrel.yaml': CONFIG, 'jobs/ops/a.yaml': job('a') + job('a2'), 'jobs/ops/db/b.yaml': job('b'),
                           'docs/x.yaml': job('ignored')])

        when:
        def r1 = sync.push(1, 'o/r', 'main')

        then:
        r1.ok() && r1.full()
        target.jobs.ops.keySet() == ['a', 'a2'] as Set
        target.jobs.db.keySet() == ['b'] as Set
        target.synced['o/r@main'] == gh.branches.main.last()
        gh.tokenRequests == 1

        when: 'a2 moves to a new file, b is removed, a is edited'
        target.log.clear()
        gh.change('main', ['jobs/ops/a.yaml': job('a', 'a-edited'), 'jobs/ops/c.yaml': job('a2'), 'jobs/ops/db/b.yaml': null])
        def r2 = sync.push(1, 'o/r', 'main')

        then: 'a2 keeps its identity; only b is deleted'
        r2.ok() && !r2.full()
        target.log == ['upsert ops/jobs/ops/a.yaml', 'upsert ops/jobs/ops/c.yaml', 'delete db/b']
        target.jobs.ops.a.name == 'a-edited'
        target.jobs.ops.a2.path == 'jobs/ops/c.yaml'
        target.jobs.ops.a2.branch == 'main'
        target.jobs.db.isEmpty()

        when: 'the same head again'
        def r3 = sync.push(1, 'o/r', 'main')

        then:
        r3.skipped() && r3.reason().startsWith('already synced')
    }

    def "a renamed file moves its jobs without deleting them"() {
        given:
        gh.commit('main', ['kestrel.yaml': CONFIG, 'jobs/ops/a.yaml': job('a')])
        sync.push(1, 'o/r', 'main')
        target.log.clear()

        when:
        gh.change('main', ['jobs/ops/a.yaml': null, 'jobs/ops/renamed.yaml': job('a')])
        sync.push(1, 'o/r', 'main')

        then:
        target.log == ['upsert ops/jobs/ops/renamed.yaml']
        target.jobs.ops.a.path == 'jobs/ops/renamed.yaml'
    }

    def "a job removed from a file is deleted; onDelete: keep keeps it"() {
        given:
        gh.commit('main', ['kestrel.yaml': cfg, 'jobs/ops/a.yaml': job('a') + job('gone')])
        sync.push(1, 'o/r', 'main')

        when:
        gh.change('main', ['jobs/ops/a.yaml': job('a')])
        def r = sync.push(1, 'o/r', 'main')

        then:
        r.deleted() == deleted
        target.jobs.ops.containsKey('gone') == kept

        where:
        cfg                               | deleted  | kept
        CONFIG                            | ['gone'] | false
        CONFIG + 'onDelete: keep\n'       | []       | true
    }

    def "a force push syncs the whole tree and deletes this repo's jobs no file holds"() {
        given:
        gh.commit('main', ['kestrel.yaml': CONFIG, 'jobs/ops/a.yaml': job('a'), 'jobs/ops/b.yaml': job('b')])
        sync.push(1, 'o/r', 'main')
        target.jobs.ops.ui = [repo: null, name: 'made in the UI']

        when:
        gh.forcePush('main', ['kestrel.yaml': CONFIG, 'jobs/ops/a.yaml': job('a')])
        def r = sync.push(1, 'o/r', 'main')

        then:
        r.full()
        r.deleted() == ['b']
        target.jobs.ops.keySet() == ['a', 'ui'] as Set
    }

    def "a last synced commit GitHub no longer has (repository recreated, history collected) syncs the whole tree"() {
        given:
        gh.commit('main', ['kestrel.yaml': CONFIG, 'jobs/ops/a.yaml': job('a'), 'jobs/ops/b.yaml': job('b')])
        sync.push(1, 'o/r', 'main')

        when:
        gh.change('main', ['jobs/ops/b.yaml': null])
        gh.commits.remove(target.synced['o/r@main'])
        def r = sync.push(1, 'o/r', 'main')

        then:
        r.ok()
        r.full()
        r.deleted() == ['b']
    }

    def "a failing file is reported and the commit is not recorded, so the next push retries"() {
        given:
        gh.commit('main', ['kestrel.yaml': CONFIG, 'jobs/ops/a.yaml': job('a')])
        sync.push(1, 'o/r', 'main')
        def good = target.synced['o/r@main']

        when:
        gh.change('main', ['jobs/ops/a.yaml': job('a', 'bad'), 'jobs/ops/b.yaml': '- name: [\n'])
        def r = sync.push(1, 'o/r', 'main')

        then:
        !r.ok()
        r.problems()*.path() as Set == ['jobs/ops/a.yaml', 'jobs/ops/b.yaml'] as Set
        target.synced['o/r@main'] == good
        target.jobs.ops.containsKey('a')

        when: 'fixed by the next push'
        gh.change('main', ['jobs/ops/a.yaml': job('a', 'fixed'), 'jobs/ops/b.yaml': job('b')])
        def r2 = sync.push(1, 'o/r', 'main')

        then:
        r2.ok()
        target.jobs.ops.keySet() == ['a', 'b'] as Set
        target.synced['o/r@main'] == gh.branches.main.last()
    }

    def "pushes to other branches, or without kestrel.yaml, are skipped"() {
        given:
        gh.commit('main', ['jobs/ops/a.yaml': job('a')])
        gh.commit('dev', ['kestrel.yaml': CONFIG, 'jobs/ops/a.yaml': job('a')])

        expect:
        sync.push(1, 'o/r', 'main').reason() == "no kestrel.yaml at ${gh.branches.main.last()[0..6]}"
        sync.push(1, 'o/r', 'dev').reason() == 'kestrel.yaml tracks main, not dev'
        sync.push(1, 'o/r', 'gone').skipped()
        target.jobs.isEmpty()
    }

    def "pull request check: annotations on the lines of the jobs at fault"() {
        given:
        def base = gh.commit('main', ['kestrel.yaml': CONFIG, 'jobs/ops/a.yaml': job('a'), 'jobs/ops/old.yaml': job('old')])
        def head = gh.change('main', [
                'jobs/ops/a.yaml'  : job('a') + job('x', 'bad'),
                'jobs/ops/b.yaml'  : "- name: no-uuid\n" + job('a'),
                'jobs/ops/c.yaml'  : 'name: [',
                'jobs/ops/old.yaml': null,
                'README.md'        : 'not a job'])
        gh.pulls[7L] = [base, head]

        when:
        def check = sync.pullRequest(1, 'o/r', 7, 'main', head)
        def run = gh.checkRuns[0]

        then:
        check.conclusion() == 'failure'
        run.name == 'Kestrel' && run.head_sha == head && run.status == 'completed' && run.conclusion == 'failure'
        run.annotations.collect { "${it.path}:${it.start_line} ${it.message}".toString() }.findAll { !it.startsWith('jobs/ops/c') } as Set == [
                'jobs/ops/a.yaml:6 bad job',
                "jobs/ops/b.yaml:1 job 'no-uuid' needs a uuid, so that Kestrel can update it in place",
                'jobs/ops/b.yaml:2 uuid a is also used in jobs/ops/a.yaml',
        ] as Set
        run.annotations.find { it.path == 'jobs/ops/c.yaml' }.message.startsWith('not valid YAML: ')
        run.output.title == '4 problem(s) in job definitions'
        run.output.summary.contains('Checked 3 job file(s), 4 job(s)')
        run.output.summary.contains('Merging deletes the jobs of these removed files (unless they moved to another file): jobs/ops/old.yaml.')
        target.jobs.isEmpty()
    }

    def "pull request check: success, and none for a PR into an untracked branch"() {
        given:
        def base = gh.commit('main', ['kestrel.yaml': CONFIG])
        def head = gh.change('main', ['jobs/ops/a.yaml': job('a')])
        gh.pulls[8L] = [base, head]

        when:
        def ok = sync.pullRequest(1, 'o/r', 8, 'main', head)
        def none = sync.pullRequest(1, 'o/r', 8, 'release', head)

        then:
        ok.conclusion() == 'success'
        ok.title() == 'Job definitions are valid'
        none == null
        gh.checkRuns.size() == 1
    }

    def "more than 50 annotations go out in batches, the last one completing the run"() {
        given:
        def base = gh.commit('main', ['kestrel.yaml': CONFIG])
        def head = gh.change('main', ['jobs/ops/a.yaml': (1..60).collect { "- name: n${it}\n" }.join()])
        gh.pulls[9L] = [base, head]

        when:
        sync.pullRequest(1, 'o/r', 9, 'main', head)

        then:
        gh.checkRuns[0].annotations.size() == 60
        gh.checkRuns[0].status == 'completed'
        gh.requests.count { it.startsWith('PATCH') } == 2
    }
}
