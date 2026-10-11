package org.rundeck.kestrel.github;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A repository's {@code kestrel.yaml} (at the repository root):
 * <pre>
 * branch: main          # the branch Kestrel tracks (default main)
 * onDelete: delete      # delete (default) or keep jobs whose file is removed
 * projects:
 *   - path: jobs/ops    # job files (*.yaml, *.yml) at or below this directory
 *     project: ops      # the Rundeck project they belong to (it must exist)
 * </pre>
 * A file belongs to the mapping with the longest matching path. Each job file holds Rundeck job
 * YAML (a list of jobs), each with a {@code uuid}.
 */
public final class RepoConfig {
    public static final String FILE = "kestrel.yaml";

    public record Mapping(String path, String project) {
    }

    private final String branch;
    private final boolean deleteRemoved;
    private final List<Mapping> mappings;
    private final List<String> errors;

    private RepoConfig(String branch, boolean deleteRemoved, List<Mapping> mappings, List<String> errors) {
        this.branch = branch;
        this.deleteRemoved = deleteRemoved;
        this.mappings = Collections.unmodifiableList(mappings);
        this.errors = Collections.unmodifiableList(errors);
    }

    public static RepoConfig parse(String text) {
        List<String> errors = new ArrayList<>();
        Object doc;
        try {
            doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (RuntimeException e) {
            return new RepoConfig("main", true, List.of(), List.of(FILE + " is not valid YAML: " + firstLine(e.getMessage())));
        }
        if (!(doc instanceof Map<?, ?> map)) {
            return new RepoConfig("main", true, List.of(), List.of(FILE + " must be a mapping with branch, onDelete and projects"));
        }
        String branch = str(map.get("branch"), "main");
        String onDelete = str(map.get("onDelete"), "delete");
        if (!onDelete.equals("delete") && !onDelete.equals("keep")) {
            errors.add("onDelete must be delete or keep, not " + onDelete);
        }
        List<Mapping> mappings = new ArrayList<>();
        Object projects = map.get("projects");
        if (!(projects instanceof List<?> list) || list.isEmpty()) {
            errors.add("projects must list at least one {path, project} mapping");
        } else {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m) || str(m.get("project"), "").isEmpty() || m.get("path") == null) {
                    errors.add("each projects entry needs path and project: " + o);
                    continue;
                }
                String path = str(m.get("path"), "").replaceAll("^/+|/+$", "");
                if (path.equals(".")) {
                    path = "";
                }
                if (path.contains("..")) {
                    errors.add("path must not contain '..': " + path);
                    continue;
                }
                mappings.add(new Mapping(path, str(m.get("project"), "")));
            }
        }
        for (String k : List.of("branch", "onDelete", "projects")) {
            map.remove(k);
        }
        if (!map.isEmpty()) {
            errors.add("unknown keys in " + FILE + ": " + map.keySet());
        }
        return new RepoConfig(branch, onDelete.equals("delete"), mappings, errors);
    }

    public String branch() {
        return branch;
    }

    public boolean deleteRemoved() {
        return deleteRemoved;
    }

    public List<Mapping> mappings() {
        return mappings;
    }

    public List<String> errors() {
        return errors;
    }

    /** @return the project a job file at this path belongs to, or null if it is not a mapped job file */
    public String projectFor(String file) {
        if (file == null || !(file.endsWith(".yaml") || file.endsWith(".yml")) || file.equals(FILE)) {
            return null;
        }
        Mapping best = null;
        for (Mapping m : mappings) {
            boolean under = m.path().isEmpty() || file.startsWith(m.path() + "/");
            if (under && (best == null || m.path().length() > best.path().length())) {
                best = m;
            }
        }
        return best == null ? null : best.project();
    }

    private static String str(Object o, String dflt) {
        return o == null ? dflt : o.toString().trim();
    }

    static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
