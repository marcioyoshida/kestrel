package rundeck.controllers

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.rundeck.kestrel.app.github.KestrelGitHub
import org.rundeck.kestrel.github.GitHubException
import org.rundeck.kestrel.github.GitHubSync
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext

/**
 * Kestrel's GitHub App webhook (ADR 0001 §5): {@code POST /api/<v>/kestrel/github/webhook}.
 * Authenticated by GitHub's HMAC signature, not by a Rundeck token (SetUserInterceptor lets this
 * action through). The work happens before the response so the outcome shows in GitHub's delivery
 * log: 200 when done, 422 with the problems when a sync failed, 202 when the event is ignored.
 */
class KestrelGithubController {
    static allowedMethods = [webhook: 'POST']
    static final List<String> PR_ACTIONS = ['opened', 'synchronize', 'reopened', 'edited']

    @Autowired
    ApplicationContext applicationContext

    def webhook() {
        KestrelGitHub gh = applicationContext.containsBean('kestrelGitHub') ? applicationContext.getBean('kestrelGitHub', KestrelGitHub) : null
        if (!gh) {
            return reply(404, [error: 'the GitHub App integration is not configured'])
        }
        byte[] body = request.inputStream.bytes
        if (!gh.verify(body, request.getHeader('X-Hub-Signature-256'))) {
            log.warn("Kestrel GitHub: rejected delivery ${request.getHeader('X-GitHub-Delivery')}: bad signature")
            return reply(401, [error: 'invalid signature'])
        }
        String event = request.getHeader('X-GitHub-Event')
        Map payload
        try {
            payload = new JsonSlurper().parse(body) as Map
        } catch (Exception ignored) {
            return reply(400, [error: 'the webhook content type must be application/json'])
        }
        if (event == 'ping') {
            return reply(200, [ok: true, zen: payload.zen])
        }
        String repo = payload.repository?.full_name
        if (!gh.allowed(repo)) {
            log.warn("Kestrel GitHub: ${event} from ${repo} refused: not in kestrel.github.repos")
            return reply(403, [error: "repository ${repo} is not allowed to sync (kestrel.github.repos)".toString()])
        }
        Long installation = payload.installation?.id as Long
        if (!installation) {
            return reply(400, [error: 'not a GitHub App delivery (no installation)'])
        }
        try {
            switch (event) {
                case 'push':
                    String ref = payload.ref
                    if (!ref?.startsWith('refs/heads/') || payload.deleted) {
                        return reply(202, [ignored: "push to ${ref}${payload.deleted ? ' (deleted)' : ''}".toString()])
                    }
                    GitHubSync.Result r = gh.sync.push(installation, repo, ref.substring('refs/heads/'.length()))
                    if (r.skipped()) {
                        return reply(202, [ignored: r.reason()])
                    }
                    return reply(r.ok() ? 200 : 422, [
                        commit  : r.commit(), full: r.full(), upserted: r.upserted(), deleted: r.deleted(),
                        problems: r.problems().collect { "${it.path()}:${it.line()}: ${it.message()}".toString() }])
                case 'pull_request':
                    if (!(payload.action in PR_ACTIONS)) {
                        return reply(202, [ignored: "pull_request ${payload.action}".toString()])
                    }
                    return check(gh, installation, repo, [payload.pull_request])
                case 'check_run':
                case 'check_suite':
                    Map item = payload[event] as Map
                    if (payload.action != 'rerequested' || (event == 'check_run' && item?.name != GitHubSync.CHECK_NAME)) {
                        return reply(202, [ignored: "${event} ${payload.action}".toString()])
                    }
                    return check(gh, installation, repo, item.pull_requests as List)
                default:
                    return reply(202, [ignored: event])
            }
        } catch (GitHubException e) {
            log.error("Kestrel GitHub: ${event} from ${repo}: ${e.message}")
            return reply(502, [error: e.message])
        }
    }

    private def check(KestrelGitHub gh, long installation, String repo, List prs) {
        def results = (prs ?: []).collect { Map pr ->
            def c = gh.sync.pullRequest(installation, repo, pr.number as long, pr.base?.ref as String, pr.head?.sha as String)
            [number: pr.number, conclusion: c?.conclusion() ?: 'not tracked']
        }
        reply(200, [checks: results])
    }

    private def reply(int status, Map body) {
        response.status = status
        render(contentType: 'application/json', text: JsonOutput.toJson(body))
        null
    }
}
