package dev.emitkit.spec.lint;

import dev.emitkit.spec.model.Operation;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Maps JSON paths such as {@code $.paths['/pets/{id}'].get} to lines of the raw document. Aliases
 * expand within limits; a recursive, too large or too deep document gives a {@link #failure()}.
 */
public final class LineIndex {

  private static final String ROOT = "$";
  private static final int MAX_NODES = 500_000;
  private static final int MAX_DEPTH = 256;

  private record Located(Node node, int line) {}

  private final Map<String, Located> nodes;
  // Container path to the $ref targets found directly in it, sorted by path.
  private final TreeMap<String, List<String>> refs;
  private final @Nullable String failure;

  private LineIndex(
      Map<String, Located> nodes, TreeMap<String, List<String>> refs, @Nullable String failure) {
    this.nodes = nodes;
    this.refs = refs;
    this.failure = failure;
  }

  /** Indexes the document. A document that cannot be indexed safely yields a failure instead. */
  public static LineIndex of(String rawContent) {
    var walker = new Walker();
    var options = new LoaderOptions();
    // Alias expansion is bounded by the walk instead, and nesting matches swagger-parser.
    options.setMaxAliasesForCollections(Integer.MAX_VALUE);
    options.setNestingDepthLimit(50);
    try {
      Node root = new Yaml(options).compose(new StringReader(rawContent));
      if (root != null) {
        walker.walk(root, ROOT, startLine(root));
      }
      return new LineIndex(walker.nodes, walker.refs, null);
    } catch (YAMLException | IllegalStateException e) {
      return new LineIndex(
          Map.of(), new TreeMap<>(), "could not index the document: " + e.getMessage());
    }
  }

  /** Why the document was not indexed, or null when the index is complete. */
  public @Nullable String failure() {
    return failure;
  }

  /** The 1-based line of the node at the path, or null when there is no such node. */
  public @Nullable Integer line(String jsonPath) {
    var found = nodes.get(jsonPath);
    return found == null ? null : found.line();
  }

  /** The line of the first candidate that exists, so the preferred path comes first. */
  public @Nullable Integer lineOf(String... candidates) {
    for (String candidate : candidates) {
      Integer line = line(candidate);
      if (line != null) {
        return line;
      }
    }
    return null;
  }

  public boolean exists(String jsonPath) {
    return nodes.containsKey(jsonPath);
  }

  /** The text of a scalar node, or null when the node is missing or not a scalar. */
  public @Nullable String scalar(String jsonPath) {
    var found = nodes.get(jsonPath);
    return found != null && found.node() instanceof ScalarNode scalar ? scalar.getValue() : null;
  }

  /** The item count of a sequence node; 0 when the node is missing or not a sequence. */
  public int itemCount(String jsonPath) {
    var found = nodes.get(jsonPath);
    return found != null && found.node() instanceof SequenceNode seq ? seq.getValue().size() : 0;
  }

  /** The scalar keys of a mapping node in document order; empty when not a mapping. */
  public List<String> keys(String jsonPath) {
    var keys = new ArrayList<String>();
    var found = nodes.get(jsonPath);
    if (found != null && found.node() instanceof MappingNode map) {
      for (NodeTuple tuple : map.getValue()) {
        if (tuple.getKeyNode() instanceof ScalarNode key) {
          keys.add(key.getValue());
        }
      }
    }
    return keys;
  }

  /** Every {@code $ref} value at any depth under the path, in document order. */
  public List<String> refsUnder(String jsonPath) {
    // Containers that start with the path form one contiguous range of the sorted map.
    var out = new ArrayList<String>();
    for (var entry : refs.subMap(jsonPath, jsonPath + '\uFFFF').entrySet()) {
      String container = entry.getKey();
      if (container.equals(jsonPath)
          || container.startsWith(jsonPath + ".")
          || container.startsWith(jsonPath + "[")) {
        out.addAll(entry.getValue());
      }
    }
    return out;
  }

  /** {@code $.paths['/pets/{id}']}: the path item of a route template. */
  public static String pathItemPath(String route) {
    return member("$.paths", route);
  }

  /** {@code $.paths['/pets/{id}'].get}: the operation, keyed by its lower-case method. */
  public static String operationPath(Operation op) {
    return pathItemPath(op.path()) + "." + op.method().toLowerCase(Locale.ROOT);
  }

  /** {@code #/components/schemas/Pet} becomes {@code $.components.schemas.Pet}. */
  public static String jsonPathOfRef(String ref) {
    String path = ROOT;
    for (String segment : ref.substring(2).split("/")) {
      path = member(path, segment);
    }
    return path;
  }

  /** Appends {@code .key} for an identifier, and {@code ['key']} otherwise. */
  public static String member(String parent, String key) {
    return key.matches("[A-Za-z_][A-Za-z0-9_]*") ? parent + "." + key : parent + "['" + key + "']";
  }

  /** Walks the expanded document, one visit per (alias-expanded) node, with limits. */
  private static final class Walker {

    final Map<String, Located> nodes = new HashMap<>();
    final TreeMap<String, List<String>> refs = new TreeMap<>();
    // The nodes on the current path. A node seen again on it is a recursive alias.
    private final Set<Node> ancestors = Collections.newSetFromMap(new IdentityHashMap<>());
    private int visited;

    void walk(Node node, String path, int line) {
      if (++visited > MAX_NODES) {
        throw new IllegalStateException(
            "it has more than " + MAX_NODES + " nodes once aliases are expanded");
      }
      if (!ancestors.add(node)) {
        throw new IllegalStateException("it contains a recursive alias");
      }
      if (ancestors.size() > MAX_DEPTH) {
        throw new IllegalStateException("it is nested more than " + MAX_DEPTH + " levels deep");
      }
      nodes.put(path, new Located(node, line));
      if (node instanceof MappingNode map) {
        for (NodeTuple tuple : map.getValue()) {
          if (tuple.getKeyNode() instanceof ScalarNode key) {
            if ("$ref".equals(key.getValue())
                && tuple.getValueNode() instanceof ScalarNode target) {
              refs.computeIfAbsent(path, k -> new ArrayList<>()).add(target.getValue());
            }
            walk(tuple.getValueNode(), member(path, key.getValue()), startLine(key));
          }
        }
      } else if (node instanceof SequenceNode seq) {
        List<Node> items = seq.getValue();
        for (int i = 0; i < items.size(); i++) {
          walk(items.get(i), path + "[" + i + "]", startLine(items.get(i)));
        }
      }
      ancestors.remove(node);
    }
  }

  private static int startLine(Node node) {
    return node.getStartMark().getLine() + 1;
  }
}
