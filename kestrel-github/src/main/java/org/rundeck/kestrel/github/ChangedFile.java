package org.rundeck.kestrel.github;

/**
 * One file of a compare: {@code status} is GitHub's (added, modified, removed, renamed, copied,
 * changed, unchanged); {@code previousPath} is set for renames.
 */
public record ChangedFile(String path, String status, String previousPath) {
    public boolean removed() {
        return "removed".equals(status);
    }

    public boolean renamed() {
        return "renamed".equals(status) && previousPath != null;
    }
}
