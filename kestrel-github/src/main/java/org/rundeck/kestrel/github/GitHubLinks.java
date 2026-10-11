package org.rundeck.kestrel.github;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Browser links to files on GitHub (or GitHub Enterprise), from the API URL Kestrel calls. */
public final class GitHubLinks {
    private final String web;

    /** @param apiUrl {@code https://api.github.com}, or {@code https://<host>/api/v3} for GitHub Enterprise */
    public GitHubLinks(String apiUrl) {
        String api = apiUrl.replaceAll("/+$", "");
        URI u = URI.create(api);
        if ("api.github.com".equalsIgnoreCase(u.getHost())) {
            web = "https://github.com";
        } else {
            web = api.replaceAll("/api/v3$", "");
        }
    }

    /** GitHub's editor for the file on the branch */
    public String edit(String repo, String branch, String path) {
        return web + "/" + repo + "/edit/" + segments(branch) + "/" + segments(path);
    }

    /** The file as of the commit */
    public String blob(String repo, String commit, String path) {
        return web + "/" + repo + "/blob/" + commit + "/" + segments(path);
    }

    private static String segments(String p) {
        List<String> parts = new ArrayList<>();
        for (String s : p.split("/")) {
            parts.add(URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return String.join("/", parts);
    }
}
