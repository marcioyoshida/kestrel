package org.rundeck.kestrel.github;

/** A GitHub API call failed; {@link #status} is the HTTP status, or 0 if no response arrived. */
public class GitHubException extends RuntimeException {
    private final int status;

    public GitHubException(int status, String message) {
        super(message);
        this.status = status;
    }

    public GitHubException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public int getStatus() {
        return status;
    }
}
