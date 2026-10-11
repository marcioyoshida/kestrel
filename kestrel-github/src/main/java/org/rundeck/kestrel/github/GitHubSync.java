package org.rundeck.kestrel.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Push sync and pull-request checks (ADR 0001 §5).
 *
 * <p><b>Push.</b> Kestrel syncs the tracked branch's <em>current</em> head, not the pushed commit,
 * from the last commit it synced completely. Pushes handled out of order or on two pods at once
 * therefore converge on the head, and every operation is idempotent (upsert by uuid). A file's
 * jobs are upserted; jobs that left a changed or removed file and appear in no new file are
 * deleted (unless {@code onDelete: keep}), so moving a job between files keeps its history. The
 * first sync, a force push, a changed kestrel.yaml or a compare GitHub truncated syncs the whole
 * tree, deleting this repo's jobs that no file holds any more. The commit is recorded only when
 * every file synced, so a failure is retried by the next push.
 *
 * <p><b>Pull request.</b> A "Kestrel" check run on the head commit validates kestrel.yaml and every
 * added or modified job file, through the same validation as a save, without saving.
 */
public class GitHubSync {
    public static final String CHECK_NAME = "Kestrel";
    private static final Logger LOG = LoggerFactory.getLogger(GitHubSync.class);

    /** What a push sync did. */
    public record Result(String commit, boolean full, List<String> upserted, List<String> deleted,
                         List<GitHubClient.Annotation> problems, boolean skipped, String reason) {
        public boolean ok() {
            return problems.isEmpty();
        }

        static Result skip(String reason) {
            return new Result(null, false, List.of(), List.of(), List.of(), true, reason);
        }
    }

    /** What a check run concluded. */
    public record Check(String conclusion, String title, String summary, List<GitHubClient.Annotation> annotations) {
    }

    private record FileRef(String project, String path) {
    }

    private final GitHubClient github;
    private final JobTarget target;

    public GitHubSync(GitHubClient github, JobTarget target) {
        this.github = github;
        this.target = target;
    }

    /** Syncs the branch a push went to, if kestrel.yaml at its head tracks it. */
    public Result push(long installation, String repo, String branch) {
        String head = github.branchHead(installation, repo, branch);
        if (head == null) {
            return Result.skip("branch " + branch + " no longer exists");
        }
        String configText = github.content(installation, repo, RepoConfig.FILE, head);
        if (configText == null) {
            return Result.skip("no " + RepoConfig.FILE + " at " + short7(head));
        }
        RepoConfig config = RepoConfig.parse(configText);
        if (!config.branch().equals(branch)) {
            return Result.skip(RepoConfig.FILE + " tracks " + config.branch() + ", not " + branch);
        }
        if (!config.errors().isEmpty()) {
            return new Result(head, false, List.of(), List.of(), annotate(RepoConfig.FILE, config.errors()), false, "invalid config");
        }
        String last = target.lastSynced(repo, branch);
        if (head.equals(last)) {
            return Result.skip("already synced " + short7(head));
        }
        GitHubClient.Compare cmp = last == null ? null : compare(installation, repo, last, head);
        boolean incremental = cmp != null && cmp.complete() && "ahead".equals(cmp.status())
                && cmp.files().stream().noneMatch(f -> RepoConfig.FILE.equals(f.path()) || RepoConfig.FILE.equals(f.previousPath()));
        Result r = incremental ? incremental(installation, repo, config, last, head, cmp.files())
                : full(installation, repo, config, head);
        if (r.ok()) {
            target.markSynced(repo, branch, head);
        }
        LOG.info("Kestrel GitHub: {} {} synced to {} ({}): {} file(s) upserted, {} job(s) deleted, {} problem(s)",
                repo, branch, short7(head), r.full() ? "full" : "incremental", r.upserted().size(), r.deleted().size(),
                r.problems().size());
        return r;
    }

    /** @return null (a full sync) if GitHub no longer has the last synced commit */
    private GitHubClient.Compare compare(long installation, String repo, String last, String head) {
        try {
            return github.compare(installation, repo, last, head);
        } catch (GitHubException e) {
            if (e.getStatus() != 404) {
                throw e;
            }
            LOG.warn("Kestrel GitHub: {} no longer has the last synced commit {}; syncing the whole tree", repo, short7(last));
            return null;
        }
    }

    private Result incremental(long inst, String repo, RepoConfig config, String last, String head, List<ChangedFile> files) {
        List<FileRef> upserts = new ArrayList<>();
        List<FileRef> olds = new ArrayList<>();
        for (ChangedFile f : files) {
            String project = f.removed() ? null : config.projectFor(f.path());
            if (project != null) {
                upserts.add(new FileRef(project, f.path()));
            }
            String oldPath = f.renamed() ? f.previousPath() : f.path();
            String oldProject = config.projectFor(oldPath);
            if (oldProject != null && !"added".equals(f.status())) {
                olds.add(new FileRef(oldProject, oldPath));
            }
        }
        List<GitHubClient.Annotation> problems = new ArrayList<>();
        Set<String> kept = new LinkedHashSet<>();
        List<String> upserted = apply(inst, repo, config.branch(), head, upserts, kept, problems);
        Map<String, String> gone = new LinkedHashMap<>();
        for (FileRef old : olds) {
            String text = github.content(inst, repo, old.path(), last);
            if (text == null) {
                continue;
            }
            for (JobFiles.Job j : JobFiles.parse(text).jobs()) {
                if (j.uuid() != null && !kept.contains(j.uuid())) {
                    gone.put(j.uuid(), old.project());
                }
            }
        }
        List<String> deleted = config.deleteRemoved() ? delete(repo, gone, problems) : List.of();
        return new Result(head, false, upserted, deleted, problems, false, null);
    }

    private Result full(long inst, String repo, RepoConfig config, String head) {
        List<FileRef> upserts = new ArrayList<>();
        for (String path : github.tree(inst, repo, head)) {
            String project = config.projectFor(path);
            if (project != null) {
                upserts.add(new FileRef(project, path));
            }
        }
        List<GitHubClient.Annotation> problems = new ArrayList<>();
        Set<String> kept = new LinkedHashSet<>();
        List<String> upserted = apply(inst, repo, config.branch(), head, upserts, kept, problems);
        Map<String, String> gone = new LinkedHashMap<>();
        if (problems.isEmpty()) {
            // a file that failed to parse still holds its jobs: delete nothing unless every file read
            for (String project : new LinkedHashSet<>(config.mappings().stream().map(RepoConfig.Mapping::project).toList())) {
                for (String uuid : target.managedJobs(repo, project)) {
                    if (!kept.contains(uuid)) {
                        gone.put(uuid, project);
                    }
                }
            }
        }
        List<String> deleted = config.deleteRemoved() ? delete(repo, gone, problems) : List.of();
        return new Result(head, true, upserted, deleted, problems, false, null);
    }

    private List<String> apply(long inst, String repo, String branch, String head, List<FileRef> upserts, Set<String> kept,
                               List<GitHubClient.Annotation> problems) {
        List<String> upserted = new ArrayList<>();
        for (FileRef f : upserts) {
            String text = github.content(inst, repo, f.path(), head);
            if (text == null) {
                continue;
            }
            JobFiles.Parsed parsed = JobFiles.parse(text);
            if (!parsed.ok()) {
                problems.add(new GitHubClient.Annotation(f.path(), parsed.errorLine(), "failure", parsed.error()));
                continue;
            }
            parsed.jobs().stream().map(JobFiles.Job::uuid).filter(u -> u != null).forEach(kept::add);
            List<JobTarget.Problem> p = target.upsert(repo, branch, f.project(), f.path(), head, text);
            problems.addAll(annotate(f.path(), parsed, p));
            if (p.isEmpty()) {
                upserted.add(f.path());
            }
        }
        return upserted;
    }

    private List<String> delete(String repo, Map<String, String> gone, List<GitHubClient.Annotation> problems) {
        List<String> deleted = new ArrayList<>();
        gone.forEach((uuid, project) -> {
            List<JobTarget.Problem> p = target.delete(repo, project, uuid);
            if (p.isEmpty()) {
                deleted.add(uuid);
            }
            p.forEach(x -> problems.add(new GitHubClient.Annotation(RepoConfig.FILE, 1, "failure",
                    "deleting job " + uuid + " from " + project + ": " + x.message())));
        });
        return deleted;
    }

    /**
     * Runs the check for a pull request into the tracked branch, publishing a check run.
     *
     * @return the check's conclusion, or null when kestrel.yaml at the head does not track the base branch
     */
    public Check pullRequest(long installation, String repo, long number, String baseBranch, String headSha) {
        String configText = github.content(installation, repo, RepoConfig.FILE, headSha);
        if (configText == null) {
            return null;
        }
        RepoConfig config = RepoConfig.parse(configText);
        if (!config.errors().isEmpty() || config.branch().equals(baseBranch)) {
            long id = github.createCheckRun(installation, repo, headSha, CHECK_NAME);
            Check check;
            try {
                check = check(installation, repo, number, headSha, config);
            } catch (RuntimeException e) {
                LOG.warn("Kestrel GitHub: check of {}#{} failed", repo, number, e);
                check = new Check("failure", "Kestrel could not check this pull request",
                        "The check failed before it finished: " + e.getMessage(), List.of());
            }
            github.completeCheckRun(installation, repo, id, check.conclusion(), check.title(), check.summary(), check.annotations());
            LOG.info("Kestrel GitHub: check of {}#{} at {}: {} ({} annotation(s))", repo, number, short7(headSha),
                    check.conclusion(), check.annotations().size());
            return check;
        }
        return null;
    }

    private Check check(long inst, String repo, long number, String head, RepoConfig config) {
        List<GitHubClient.Annotation> annotations = new ArrayList<>(annotate(RepoConfig.FILE, config.errors()));
        int files = 0;
        int jobs = 0;
        List<String> removed = new ArrayList<>();
        Map<String, String> seen = new LinkedHashMap<>();
        for (ChangedFile f : github.pullRequestFiles(inst, repo, number)) {
            String project = config.projectFor(f.path());
            if (f.removed()) {
                if (project != null) {
                    removed.add(f.path());
                }
                continue;
            }
            if (project == null) {
                continue;
            }
            files++;
            String text = github.content(inst, repo, f.path(), head);
            if (text == null) {
                continue;
            }
            JobFiles.Parsed parsed = JobFiles.parse(text);
            if (!parsed.ok()) {
                annotations.add(new GitHubClient.Annotation(f.path(), parsed.errorLine(), "failure", parsed.error()));
                continue;
            }
            jobs += parsed.jobs().size();
            for (JobFiles.Job j : parsed.jobs()) {
                if (j.uuid() == null) {
                    annotations.add(new GitHubClient.Annotation(f.path(), j.line(), "failure", JobFiles.missingUuid(j.name())));
                } else if (seen.containsKey(j.uuid())) {
                    annotations.add(new GitHubClient.Annotation(f.path(), j.line(), "failure",
                            "uuid " + j.uuid() + " is also used in " + seen.get(j.uuid())));
                } else {
                    seen.put(j.uuid(), f.path());
                }
            }
            annotations.addAll(annotate(f.path(), parsed, target.validate(repo, project, f.path(), text)));
        }
        annotations = annotations.stream().distinct().toList();   // the target repeats the uuid check
        boolean failed = annotations.stream().anyMatch(a -> "failure".equals(a.level()));
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Checked %d job file(s), %d job(s), for branch `%s`.", files, jobs, config.branch()));
        if (!removed.isEmpty()) {
            summary.append(config.deleteRemoved() ? "\n\nMerging deletes the jobs of these removed files"
                    : "\n\nThese files are removed; `onDelete: keep` leaves their jobs in place")
                    .append(" (unless they moved to another file): ").append(String.join(", ", removed)).append('.');
        }
        long failures = annotations.stream().filter(a -> "failure".equals(a.level())).count();
        String title = failed ? failures + " problem(s) in job definitions" : "Job definitions are valid";
        return new Check(failed ? "failure" : "success", title, summary.toString(), annotations);
    }

    private static List<GitHubClient.Annotation> annotate(String path, List<String> messages) {
        return messages.stream().map(m -> new GitHubClient.Annotation(path, 1, "failure", m)).toList();
    }

    private static List<GitHubClient.Annotation> annotate(String path, JobFiles.Parsed parsed, List<JobTarget.Problem> problems) {
        List<GitHubClient.Annotation> out = new ArrayList<>();
        for (JobTarget.Problem p : problems) {
            int line = p.job() >= 0 && p.job() < parsed.jobs().size() ? parsed.jobs().get(p.job()).line() : 1;
            out.add(new GitHubClient.Annotation(path, line, "failure", p.message()));
        }
        return out;
    }

    private static String short7(String sha) {
        return sha == null ? "null" : sha.substring(0, Math.min(7, sha.length()));
    }
}
