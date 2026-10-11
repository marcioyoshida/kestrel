package org.rundeck.kestrel.github

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

/**
 * An in-memory GitHub for one repository: commits are full snapshots (path -> content) in a
 * history list per branch; compare is "ahead" when the base is an ancestor of the head.
 */
class FakeGitHub {
    static final ObjectMapper JSON = new ObjectMapper()
    HttpServer server
    Map<String, Map<String, String>> commits = [:]
    Map<String, List<String>> branches = [:]
    Map<Long, List<String>> pulls = [:]           // number -> [base sha, head sha]
    List<Map> checkRuns = []
    List<String> requests = []
    int tokenRequests = 0
    int seq = 0

    String getUrl() { "http://127.0.0.1:${server.address.port}" }

    FakeGitHub start() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { HttpExchange x -> handle(x) }
        server.start()
        this
    }

    void stop() { server.stop(0) }

    String commit(String branch, Map<String, String> files) {
        String sha = String.format('%040x', ++seq)
        commits[sha] = new LinkedHashMap(files)
        branches.computeIfAbsent(branch) { [] } << sha
        sha
    }

    /** A commit on top of the branch head: null values remove files. */
    String change(String branch, Map<String, String> changes) {
        Map<String, String> files = new LinkedHashMap(commits[branches[branch].last()])
        changes.each { k, v -> v == null ? files.remove(k) : files.put(k, v) }
        commit(branch, files)
    }

    /** Rewrites the branch history (a force push). */
    String forcePush(String branch, Map<String, String> files) {
        branches[branch] = []
        commit(branch, files)
    }

    private def handle(HttpExchange x) {
        String path = x.requestURI.rawPath
        String query = x.requestURI.rawQuery ?: ''
        requests << "${x.requestMethod} ${path}".toString()
        def body = x.requestBody.bytes
        def m
        try {
            if (x.requestMethod == 'POST' && (m = path =~ '^/app/installations/(\\d+)/access_tokens$')) {
                assert x.requestHeaders.getFirst('Authorization').startsWith('Bearer ey')
                tokenRequests++
                return json(x, 201, [token: 'inst-token', expires_at: '2099-01-01T00:00:00Z'])
            }
            assert x.requestHeaders.getFirst('Authorization') == 'token inst-token'
            if ((m = path =~ '^/repos/o/r/git/ref/heads/(.+)$')) {
                def h = branches[URLDecoder.decode(m[0][1], 'UTF-8')]
                return h ? json(x, 200, [object: [sha: h.last()]]) : json(x, 404, [message: 'Not Found'])
            }
            if ((m = path =~ '^/repos/o/r/compare/(\\w+)\\.\\.\\.(\\w+)$')) {
                String base = m[0][1], head = m[0][2]
                boolean ahead = branches.values().any { h -> h.indexOf(base) >= 0 && h.indexOf(head) > h.indexOf(base) }
                return json(x, 200, [status: ahead ? 'ahead' : 'diverged', files: diff(commits[base], commits[head])])
            }
            if ((m = path =~ '^/repos/o/r/pulls/(\\d+)/files$')) {
                def (base, head) = pulls[m[0][1] as long]
                int page = (query =~ /(?:^|&)page=(\d+)/)[0][1] as int
                def files = diff(commits[base], commits[head])
                return json(x, 200, page == 1 ? files : [])
            }
            if ((m = path =~ '^/repos/o/r/git/trees/(\\w+)$')) {
                return json(x, 200, [truncated: false, tree: commits[m[0][1]].keySet().collect { [path: it, type: 'blob'] }])
            }
            if ((m = path =~ '^/repos/o/r/contents/(.+)$')) {
                String ref = (query =~ /ref=(\w+)/)[0][1]
                String file = m[0][1].split('/').collect { URLDecoder.decode(it, 'UTF-8') }.join('/')
                String text = commits[ref]?.get(file)
                if (text == null) return json(x, 404, [message: 'Not Found'])
                assert x.requestHeaders.getFirst('Accept') == 'application/vnd.github.raw'
                return send(x, 200, text.getBytes('UTF-8'))
            }
            if (x.requestMethod == 'POST' && path == '/repos/o/r/check-runs') {
                Map run = JSON.readValue(body, Map) + [id: checkRuns.size() + 1, annotations: []]
                checkRuns << run
                return json(x, 201, [id: run.id])
            }
            if (x.requestMethod == 'PATCH' && (m = path =~ '^/repos/o/r/check-runs/(\\d+)$')) {
                Map run = checkRuns[(m[0][1] as int) - 1]
                Map patch = JSON.readValue(body, Map)
                run.annotations.addAll(patch.output.annotations)
                run.putAll(patch.findAll { it.key != 'output' })
                run.output = patch.output.findAll { it.key != 'annotations' }
                return json(x, 200, [id: run.id])
            }
            json(x, 404, [message: "no fake for ${x.requestMethod} ${path}"])
        } catch (Throwable e) {
            json(x, 500, [message: e.toString()])
        }
    }

    private static List<Map> diff(Map<String, String> a, Map<String, String> b) {
        def out = []
        b.each { p, t ->
            if (!a.containsKey(p)) {
                def from = a.find { op, ot -> ot == t && !b.containsKey(op) }
                out << (from ? [filename: p, status: 'renamed', previous_filename: from.key] : [filename: p, status: 'added'])
            } else if (a[p] != t) {
                out << [filename: p, status: 'modified']
            }
        }
        a.each { p, t ->
            if (!b.containsKey(p) && !out.any { it.previous_filename == p }) out << [filename: p, status: 'removed']
        }
        out
    }

    private static void json(HttpExchange x, int status, Object o) {
        x.responseHeaders.add('Content-Type', 'application/json')
        send(x, status, JSON.writeValueAsBytes(o))
    }

    private static void send(HttpExchange x, int status, byte[] b) {
        x.sendResponseHeaders(status, b.length ?: -1)
        if (b.length) x.responseBody.write(b)
        x.close()
    }
}
