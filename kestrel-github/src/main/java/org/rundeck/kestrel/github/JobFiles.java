package org.rundeck.kestrel.github;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** Reads the jobs of a Rundeck job YAML file: each job's uuid and the line it starts on. */
public final class JobFiles {
    /** A job in a file; {@code uuid} is null when the job has none, {@code line} is 1-based. */
    public record Job(String uuid, String name, int line) {
    }

    /** The jobs of a file, or the reason it is not a job file. */
    public record Parsed(List<Job> jobs, String error, int errorLine) {
        public boolean ok() {
            return error == null;
        }
    }

    private JobFiles() {
    }

    public static Parsed parse(String text) {
        Node root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).compose(new StringReader(text));
        } catch (RuntimeException e) {
            int line = 1;
            if (e instanceof org.yaml.snakeyaml.error.MarkedYAMLException m && m.getProblemMark() != null) {
                line = m.getProblemMark().getLine() + 1;
            }
            return new Parsed(List.of(), "not valid YAML: " + RepoConfig.firstLine(e.getMessage()), line);
        }
        if (!(root instanceof SequenceNode seq)) {
            return new Parsed(List.of(), "a job file must be a YAML list of Rundeck jobs", 1);
        }
        List<Job> jobs = new ArrayList<>();
        for (Node n : seq.getValue()) {
            String uuid = null;
            String id = null;
            String name = null;
            if (n instanceof MappingNode m) {
                for (NodeTuple t : m.getValue()) {
                    if (t.getKeyNode() instanceof ScalarNode k && t.getValueNode() instanceof ScalarNode v) {
                        switch (k.getValue()) {
                            case "uuid" -> uuid = v.getValue();
                            case "id" -> id = v.getValue();
                            case "name" -> name = v.getValue();
                            default -> {
                            }
                        }
                    }
                }
            }
            String u = uuid != null && !uuid.isBlank() ? uuid.trim() : id != null && !id.isBlank() ? id.trim() : null;
            jobs.add(new Job(u, name, n.getStartMark().getLine() + 1));
        }
        return new Parsed(jobs, null, 0);
    }
}
