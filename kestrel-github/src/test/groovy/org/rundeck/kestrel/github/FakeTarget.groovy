package org.rundeck.kestrel.github

/** Rundeck as a map: project -> uuid -> [repo, branch, path, commit]. Jobs named 'bad' fail validation. */
class FakeTarget implements JobTarget {
    Map<String, Map<String, Map>> jobs = [:].withDefault { [:] }
    Map<String, String> synced = [:]
    List<String> log = []

    @Override
    List<Problem> validate(String repo, String project, String path, String content) {
        def out = []
        JobFiles.parse(content).jobs().eachWithIndex { j, i -> if (j.name() == 'bad') out << new Problem(i, 'bad job') }
        out
    }

    @Override
    List<Problem> upsert(String repo, String branch, String project, String path, String commit, String content) {
        def problems = validate(repo, project, path, content)
        if (problems) return problems
        JobFiles.parse(content).jobs().each { j ->
            jobs[project][j.uuid()] = [repo: repo, branch: branch, path: path, commit: commit, name: j.name()]
        }
        log << "upsert ${project}/${path}".toString()
        []
    }

    @Override
    List<Problem> delete(String repo, String project, String uuid) {
        if (jobs[project][uuid]?.repo == repo) {
            jobs[project].remove(uuid)
            log << "delete ${project}/${uuid}".toString()
        }
        []
    }

    @Override
    Collection<String> managedJobs(String repo, String project) {
        jobs[project].findAll { it.value.repo == repo }.keySet()
    }

    @Override
    String lastSynced(String repo, String branch) { synced["${repo}@${branch}".toString()] }

    @Override
    void markSynced(String repo, String branch, String commit) { synced["${repo}@${branch}".toString()] = commit }
}
