package org.rundeck.kestrel.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The slice of GitHub's REST API Kestrel uses, authenticated as a GitHub App installation.
 * Installation tokens (1 hour) are cached per installation and renewed 5 minutes before expiry.
 * {@code apiUrl} is {@code https://api.github.com}, a GitHub Enterprise Server {@code /api/v3}
 * URL, or a fake in tests.
 */
public class GitHubClient {
    /** A check-run annotation; level is notice, warning or failure. */
    public record Annotation(String path, int line, String level, String message) {
    }

    /** A compare's files; {@code complete} is false when GitHub truncated the list (300 files). */
    public record Compare(String status, List<ChangedFile> files, boolean complete) {
    }

    static final int COMPARE_FILE_LIMIT = 300;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String apiUrl;
    private final GitHubAppKey key;
    private final Clock clock;
    private final HttpClient http;
    private final Map<Long, Token> tokens = new ConcurrentHashMap<>();

    private record Token(String value, Instant expires) {
    }

    public GitHubClient(String apiUrl, GitHubAppKey key, Clock clock) {
        this.apiUrl = apiUrl.replaceAll("/+$", "");
        this.key = key;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public String installationToken(long installationId) {
        Token t = tokens.get(installationId);
        Instant now = clock.instant();
        if (t == null || t.expires.isBefore(now.plus(Duration.ofMinutes(5)))) {
            JsonNode r = send("POST", "/app/installations/" + installationId + "/access_tokens",
                    "Bearer " + key.jwt(now), null, "application/vnd.github+json");
            t = new Token(r.path("token").asText(), Instant.parse(r.path("expires_at").asText()));
            tokens.put(installationId, t);
        }
        return t.value;
    }

    /** @return the commit sha the branch points at, or null if there is no such branch */
    public String branchHead(long installation, String repo, String branch) {
        try {
            return get(installation, "/repos/" + repo + "/git/ref/heads/" + path(branch)).path("object").path("sha").asText(null);
        } catch (GitHubException e) {
            if (e.getStatus() == 404) {
                return null;
            }
            throw e;
        }
    }

    /** @return the files changed between two commits ({@code base...head}) */
    public Compare compare(long installation, String repo, String base, String head) {
        JsonNode r = get(installation, "/repos/" + repo + "/compare/" + base + "..." + head);
        List<ChangedFile> files = files(r.path("files"));
        return new Compare(r.path("status").asText(), files, files.size() < COMPARE_FILE_LIMIT);
    }

    /** @return every file a pull request changes (paged, up to GitHub's 3000) */
    public List<ChangedFile> pullRequestFiles(long installation, String repo, long number) {
        List<ChangedFile> all = new ArrayList<>();
        for (int page = 1; ; page++) {
            List<ChangedFile> files = files(get(installation, "/repos/" + repo + "/pulls/" + number + "/files?per_page=100&page=" + page));
            all.addAll(files);
            if (files.size() < 100) {
                return all;
            }
        }
    }

    /** @return every file path in the commit's tree */
    public List<String> tree(long installation, String repo, String sha) {
        JsonNode r = get(installation, "/repos/" + repo + "/git/trees/" + sha + "?recursive=1");
        if (r.path("truncated").asBoolean()) {
            throw new GitHubException(0, "tree of " + repo + "@" + sha + " is too large to list");
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode n : r.path("tree")) {
            if ("blob".equals(n.path("type").asText())) {
                paths.add(n.path("path").asText());
            }
        }
        return paths;
    }

    /** @return the file's content at the commit, or null if it does not exist there */
    public String content(long installation, String repo, String filePath, String ref) {
        try {
            HttpResponse<String> r = call("GET", "/repos/" + repo + "/contents/" + path(filePath) + "?ref=" + enc(ref),
                    "token " + installationToken(installation), null, "application/vnd.github.raw");
            return r.body();
        } catch (GitHubException e) {
            if (e.getStatus() == 404) {
                return null;
            }
            throw e;
        }
    }

    /** @return the id of a new in-progress check run on the commit */
    public long createCheckRun(long installation, String repo, String headSha, String name) {
        ObjectNode body = JSON.createObjectNode().put("name", name).put("head_sha", headSha).put("status", "in_progress");
        return send("POST", "/repos/" + repo + "/check-runs", "token " + installationToken(installation), body,
                "application/vnd.github+json").path("id").asLong();
    }

    /**
     * Completes a check run. GitHub takes at most 50 annotations per request and appends them, so
     * larger sets go out in batches.
     */
    public void completeCheckRun(long installation, String repo, long id, String conclusion, String title,
                                 String summary, List<Annotation> annotations) {
        int i = 0;
        do {
            ObjectNode body = JSON.createObjectNode();
            ObjectNode output = body.putObject("output").put("title", title).put("summary", summary);
            ArrayNode list = output.putArray("annotations");
            for (Annotation a : annotations.subList(i, Math.min(i + 50, annotations.size()))) {
                list.addObject().put("path", a.path()).put("start_line", a.line()).put("end_line", a.line())
                        .put("annotation_level", a.level()).put("message", a.message());
            }
            i += 50;
            if (i >= annotations.size()) {
                body.put("status", "completed").put("conclusion", conclusion);
            }
            send("PATCH", "/repos/" + repo + "/check-runs/" + id, "token " + installationToken(installation), body,
                    "application/vnd.github+json");
        } while (i < annotations.size());
    }

    private JsonNode get(long installation, String path) {
        return send("GET", path, "token " + installationToken(installation), null, "application/vnd.github+json");
    }

    private JsonNode send(String method, String path, String auth, JsonNode body, String accept) {
        try {
            String text = call(method, path, auth, body, accept).body();
            return text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
        } catch (IOException e) {
            throw new GitHubException("GitHub " + method + " " + path + ": unreadable response", e);
        }
    }

    private HttpResponse<String> call(String method, String path, String auth, JsonNode body, String accept) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", auth)
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "kestrel");
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (attempt == 1) {
                    throw new GitHubException("GitHub " + method + " " + path + ": " + e.getMessage(), e);
                }
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GitHubException("GitHub " + method + " " + path + ": interrupted", e);
            }
            if (r.statusCode() < 500) {
                break;
            }
        }
        if (r.statusCode() >= 300) {
            String text = r.body() == null ? "" : r.body();
            throw new GitHubException(r.statusCode(), "GitHub " + method + " " + path + ": HTTP " + r.statusCode() + " "
                    + text.substring(0, Math.min(300, text.length())));
        }
        return r;
    }

    private static List<ChangedFile> files(JsonNode array) {
        List<ChangedFile> files = new ArrayList<>();
        for (JsonNode f : array) {
            files.add(new ChangedFile(f.path("filename").asText(), f.path("status").asText(),
                    f.hasNonNull("previous_filename") ? f.path("previous_filename").asText() : null));
        }
        return files;
    }

    private static String path(String p) {
        List<String> parts = new ArrayList<>();
        for (String s : p.split("/")) {
            parts.add(enc(s));
        }
        return String.join("/", parts);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
