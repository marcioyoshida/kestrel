package org.rundeck.kestrel.app.github

import groovy.transform.CompileStatic
import org.rundeck.kestrel.github.GitHubAppKey
import org.rundeck.kestrel.github.GitHubClient
import org.rundeck.kestrel.github.GitHubLinks
import org.rundeck.kestrel.github.GitHubSync
import org.rundeck.kestrel.github.WebhookSignature
import org.springframework.context.ApplicationContext
import org.springframework.core.env.PropertyResolver
import rundeck.ScheduledExecution

import java.time.Clock

/**
 * GitHub App integration (ADR 0001 §5), enabled when {@code kestrel.github.app-id} is set. Every
 * key also binds from its environment form ({@code kestrel.github.app-id} -> KESTREL_GITHUB_APP_ID):
 * <ul>
 * <li>{@code app-id}, {@code private-key} (PEM), {@code webhook-secret}: from the GitHub App;</li>
 * <li>{@code repos}: the repositories allowed to sync, {@code owner/repo} or {@code owner/*},
 * comma-separated. A push from any other repository is refused;</li>
 * <li>{@code user} and {@code roles}: the Rundeck identity jobs are imported as. Its ACLs decide
 * which projects a repository may write to;</li>
 * <li>{@code api-url}: GitHub's API (default {@code https://api.github.com}).</li>
 * </ul>
 */
@CompileStatic
class KestrelGitHub {
    String appId
    String webhookSecret
    List<String> repos
    String user
    List<String> roles
    GitHubSync sync
    GitHubLinks links
    RundeckJobTarget target

    static boolean enabled(PropertyResolver config) {
        prop(config, 'kestrel.github.app-id', null) != null
    }

    static KestrelGitHub from(PropertyResolver config, RundeckJobTarget target) {
        def g = new KestrelGitHub()
        g.appId = prop(config, 'kestrel.github.app-id', null)
        g.webhookSecret = prop(config, 'kestrel.github.webhook-secret', null)
        g.repos = list(prop(config, 'kestrel.github.repos', '')).collect { it.toLowerCase() }
        g.user = prop(config, 'kestrel.github.user', 'kestrel-github')
        g.roles = list(prop(config, 'kestrel.github.roles', ''))
        def missing = [
            'kestrel.github.private-key': prop(config, 'kestrel.github.private-key', null),
            'kestrel.github.webhook-secret': g.webhookSecret,
            'kestrel.github.repos': g.repos ? 'set' : null,
            'kestrel.github.roles': g.roles ? 'set' : null,
        ].findAll { k, v -> !v }.keySet()
        if (missing) {
            throw new IllegalStateException("Kestrel GitHub App ${g.appId} is configured without: ${missing.join(', ')}")
        }
        String pem = prop(config, 'kestrel.github.private-key', null).replace('\\n', '\n')
        String apiUrl = prop(config, 'kestrel.github.api-url', 'https://api.github.com')
        def client = new GitHubClient(apiUrl, new GitHubAppKey(g.appId, pem), Clock.systemUTC())
        target.user = g.user
        target.roles = g.roles
        g.target = target
        g.links = new GitHubLinks(apiUrl)
        g.sync = new GitHubSync(client, target)
        g
    }

    /**
     * @return where the job is synced from ({@code repo, branch, path, commit, editUrl, viewUrl}), or
     * null if it is not, or the integration is off (then synced jobs are ordinary jobs again)
     */
    static Map source(ApplicationContext ctx, ScheduledExecution job) {
        if (!job?.uuid || !ctx?.containsBean('kestrelGitHub')) {
            return null
        }
        ctx.getBean('kestrelGitHub', KestrelGitHub).source(job)
    }

    Map source(ScheduledExecution job) {
        Map m = target.source(job)
        if (!m) {
            return null
        }
        String repo = m.repo as String, path = m.path as String, branch = m.branch as String, commit = m.commit as String
        m + [editUrl: branch ? links.edit(repo, branch, path) : links.blob(repo, commit, path),
             viewUrl: links.blob(repo, commit, path)]
    }

    /**
     * Synced jobs change only through GitHub: any other update or delete is refused with this
     * message. Null when the change may go ahead.
     * @param method the change's method ({@code changeinfo.method} / {@code callingAction})
     */
    static String readOnly(ApplicationContext ctx, ScheduledExecution job, String method) {
        if (method == RundeckJobTarget.META) {
            return null
        }
        Map m = source(ctx, job)
        m ? "Job ${job.extid} is synced from GitHub (${m.repo}: ${m.path}); change it there: ${m.editUrl}".toString() : null
    }

    boolean verify(byte[] body, String signature) {
        WebhookSignature.verify(webhookSecret, body, signature)
    }

    /** @return true if the repository ({@code owner/name}) may sync */
    boolean allowed(String repo) {
        if (!repo) {
            return false
        }
        String r = repo.toLowerCase()
        repos.any { String p -> p == r || (p.endsWith('/*') && r.startsWith(p[0..-2])) }
    }

    private static List<String> list(String s) {
        (s ?: '').split(',')*.trim().findAll { it } as List<String>
    }

    private static String prop(PropertyResolver config, String key, String dflt) {
        String v = config?.getProperty(key)
        if (!v) {
            v = System.getenv(key.toUpperCase().replace('.', '_').replace('-', '_'))
        }
        v ?: dflt
    }
}
