package org.rundeck.kestrel.github;

import java.util.Collection;
import java.util.List;

/**
 * Where synced jobs go: Rundeck, in rundeckapp. Problems name the job by its index in the file
 * ({@code -1} for the whole file).
 */
public interface JobTarget {
    record Problem(int job, String message) {
    }

    /** Validates a job file for the project without saving anything. */
    List<Problem> validate(String repo, String project, String path, String content);

    /** Creates or updates (by uuid) every job in the file, recording repo, path and commit. */
    List<Problem> upsert(String repo, String project, String path, String commit, String content);

    /** Deletes the job if it exists in the project and was synced from this repo. */
    List<Problem> delete(String repo, String project, String uuid);

    /** @return uuids of the project's jobs synced from this repo */
    Collection<String> managedJobs(String repo, String project);

    /** @return the commit of the repo's branch last synced completely, or null */
    String lastSynced(String repo, String branch);

    void markSynced(String repo, String branch, String commit);
}
