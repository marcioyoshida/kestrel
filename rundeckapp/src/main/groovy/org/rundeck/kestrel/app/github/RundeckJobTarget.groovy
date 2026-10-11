package org.rundeck.kestrel.app.github

import com.dtolabs.rundeck.core.authorization.UserAndRolesAuthContext
import org.rundeck.app.components.jobs.ImportedJob
import org.rundeck.core.auth.AuthConstants
import org.rundeck.kestrel.github.JobFiles
import org.rundeck.kestrel.github.JobTarget
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rundeck.ScheduledExecution

/**
 * GitHub-synced jobs into Rundeck, the way upstream's SCM import does it: Rundeck job YAML is
 * decoded and loaded with {@code dupeOption=update, uuidOption=preserve} as {@link #user} with
 * {@link #roles}, so project ACLs bound what a repository can change. Each synced job records
 * {@code repo, path, commit} in its plugin metadata ({@value #META}); only jobs carrying this
 * repository's mark are deleted. Existing jobs with no mark are adopted by the first sync that
 * holds their uuid in the same project.
 */
class RundeckJobTarget implements JobTarget {
    static final String META = 'kestrel-github'
    private static final Logger LOG = LoggerFactory.getLogger(RundeckJobTarget)

    def scheduledExecutionService
    def jobMetadataService
    def frameworkService
    def rundeckAuthContextProcessor
    def configStorageService
    String user
    List<String> roles

    @Override
    List<Problem> validate(String repo, String project, String path, String content) {
        Map pre = prepare(repo, project, content)
        if (pre.problems) {
            return pre.problems
        }
        List<Problem> problems = []
        ScheduledExecution.withNewSession { session ->
            pre.jobs.eachWithIndex { ImportedJob<ScheduledExecution> imported, int i ->
                ScheduledExecution job = imported.job
                ScheduledExecution existing = pre.existing[i]
                if (!authorized(pre.auth, job, existing, project)) {
                    problems << new Problem(i, "${user} may not ${existing ? 'update' : 'create'} this job in project ${project}")
                    return
                }
                // validate as an update would: the stored job already holds the uuid
                String uuid = job.uuid
                job.uuid = null
                Map validation = [:]
                boolean ok = scheduledExecutionService.validateJobDefinition(imported, pre.auth, [:], validation, false)
                job.uuid = uuid
                if (!ok) {
                    problems << new Problem(i, describe(job, validation))
                }
            }
            session.clear()
        }
        problems
    }

    @Override
    List<Problem> upsert(String repo, String branch, String project, String path, String commit, String content) {
        Map pre = prepare(repo, project, content)
        if (pre.problems) {
            return pre.problems
        }
        def result = scheduledExecutionService.loadImportedJobs(pre.jobs, 'update', 'preserve',
            [user: user, method: META], pre.auth)
        List<Problem> problems = result.errjobs.collect { Map e -> new Problem(((e.entrynum ?: 0) as int) - 1, e.errmsg?.toString()) }
        result.jobs.each { ScheduledExecution job ->
            jobMetadataService.setJobPluginMeta(job, META, [repo: repo, branch: branch, path: path, commit: commit])
        }
        scheduledExecutionService.issueJobChangeEvents(result.jobChangeEvents)
        problems
    }

    @Override
    List<Problem> delete(String repo, String project, String uuid) {
        ScheduledExecution job = ScheduledExecution.findByUuidAndProject(uuid, project)
        if (!job) {
            jobMetadataService.removeJobPluginMeta(project, uuid, META)
            return []
        }
        Map meta = jobMetadataService.getJobPluginMeta(job, META)
        if (meta?.repo != repo) {
            LOG.warn("Kestrel GitHub: not deleting job {} in {}: it is not synced from {}", uuid, project, repo)
            return []
        }
        def auth = rundeckAuthContextProcessor.getAuthContextForUserAndRolesAndProject(user, roles, project)
        def r = scheduledExecutionService.deleteScheduledExecutionById(uuid, auth, false, user, META)
        if (!r.success) {
            return [new Problem(-1, r.error?.message?.toString() ?: 'delete failed')]
        }
        jobMetadataService.removeJobPluginMeta(project, uuid, META)
        []
    }

    /** @return {@code [repo, branch, path, commit]} if the job is synced from GitHub, else null */
    Map source(ScheduledExecution job) {
        Map meta = jobMetadataService.getJobPluginMeta(job, META)
        meta?.repo ? meta : null
    }

    @Override
    Collection<String> managedJobs(String repo, String project) {
        jobMetadataService.getJobsPluginMeta(project, META)
            .findAll { it.pluginData?.repo == repo }
            .collect { (it.key as String) - "/${META}" }
    }

    @Override
    String lastSynced(String repo, String branch) {
        String p = statePath(repo, branch)
        configStorageService.existsFileResource(p) ? configStorageService.getFileResource(p).contents.inputStream.getText('UTF-8').trim() : null
    }

    @Override
    void markSynced(String repo, String branch, String commit) {
        configStorageService.writeFileResource(statePath(repo, branch), new ByteArrayInputStream(commit.getBytes('UTF-8')),
            ['Content-Type': 'text/plain'])
    }

    private static String statePath(String repo, String branch) {
        "kestrel/github/${repo}/${branch}".toString()
    }

    /** Decodes the file and checks each job's uuid and name against the project's jobs. */
    private Map prepare(String repo, String project, String content) {
        if (!frameworkService.existsFrameworkProject(project)) {
            return [problems: [new Problem(-1, "project ${project} does not exist".toString())]]
        }
        def parsed = scheduledExecutionService.parseUploadedFile(new ByteArrayInputStream(content.getBytes('UTF-8')), 'yaml')
        if (parsed.error || parsed.errorCode) {
            String msg = parsed.error ?: scheduledExecutionService.messageSource.getMessage(parsed.errorCode, parsed.args as Object[], parsed.errorCode, Locale.default)
            return [problems: [new Problem(-1, "not a Rundeck job file: ${msg}".toString())]]
        }
        UserAndRolesAuthContext auth = rundeckAuthContextProcessor.getAuthContextForUserAndRolesAndProject(user, roles, project)
        List<Problem> problems = []
        List<ScheduledExecution> existing = []
        parsed.jobset.eachWithIndex { ImportedJob<ScheduledExecution> imported, int i ->
            ScheduledExecution job = imported.job
            job.project = project
            ScheduledExecution found = job.uuid ? ScheduledExecution.findByUuid(job.uuid) : null
            existing << (found?.project == project ? found : null)
            if (!job.uuid) {
                problems << new Problem(i, JobFiles.missingUuid(job.jobName))
                return
            }
            if (found && found.project != project) {
                problems << new Problem(i, "uuid ${job.uuid} belongs to job '${found.generateFullName()}' in project ${found.project}".toString())
                return
            }
            if (found) {
                Map meta = jobMetadataService.getJobPluginMeta(found, META)
                if (meta?.repo && meta.repo != repo) {
                    problems << new Problem(i, "job ${job.uuid} is synced from ${meta.repo}".toString())
                    return
                }
            }
            def clash = ScheduledExecution.findAllScheduledExecutions(job.groupPath, job.jobName, project).find { it.uuid != job.uuid }
            if (clash) {
                problems << new Problem(i, "project ${project} already has a job named '${clash.generateFullName()}' (uuid ${clash.uuid})".toString())
            }
        }
        [jobs: parsed.jobset, existing: existing, auth: auth, problems: problems]
    }

    private boolean authorized(UserAndRolesAuthContext auth, ScheduledExecution job, ScheduledExecution existing, String project) {
        existing ? rundeckAuthContextProcessor.authorizeProjectJobAny(auth, existing, [AuthConstants.ACTION_UPDATE], project)
            : rundeckAuthContextProcessor.authorizeProjectJobAll(auth, job, [AuthConstants.ACTION_CREATE], project)
    }

    private String describe(ScheduledExecution job, Map validation) {
        List<String> msgs = job.errors.allErrors.collect { err ->
            String field = err.hasProperty('field') ? err.field : err.objectName
            "${field}: ${scheduledExecutionService.messageSource.getMessage(err, Locale.default)}".toString()
        }
        validation.each { k, v ->
            String text = v instanceof Map ? (v.reason ?: v.values().join('; ')) : v?.toString()
            String line = "${k}: ${text}".toString()
            if (!msgs.any { it.startsWith("${k}: ") }) {
                msgs << line
            }
        }
        msgs ? msgs.unique().join('; ') : 'invalid job definition'
    }
}
