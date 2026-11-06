package dev.emitkit.spec.lint;

import dev.emitkit.spec.model.Operation;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Maps JSON paths such as {@code $.paths['/pets/{id}'].get} to the raw document (YAML or JSON). A
 * member's line is the line of its key; a sequence item's line is the line where it starts.
 */
public final class LineIndex {

  private static final String ROOT = "$";

  private record Located(Node node, int line) {}

  private final Map<String, Located> nodes;

  private LineIndex(Map<String, Located> nodes) {
    this.nodes = nodes;
  }

  /** Indexes the document; content SnakeYAML cannot compose gives an empty index. */
  public static LineIndex of(String rawContent) {
    var nodes = new HashMap<String, Located>();
    try {
      Node root = new Yaml().compose(new StringReader(rawContent));
      if (root != null) {
        walk(root, ROOT, startLine(root), nodes);
      }
    } catch (YAMLException e) {
      nodes.clear();
    }
    return new LineIndex(nodes);
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

  /** Every {@code $ref} value at any depth under the node, in document order. */
  public List<String> refsUnder(String jsonPath) {
    var refs = new ArrayList<String>();
    var found = nodes.get(jsonPath);
    if (found != null) {
      collectRefs(found.node(), refs);
    }
    return refs;
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

  private static void walk(Node node, String path, int line, Map<String, Located> out) {
    out.put(path, new Located(node, line));
    if (node instanceof MappingNode map) {
      for (NodeTuple tuple : map.getValue()) {
        if (tuple.getKeyNode() instanceof ScalarNode key) {
          walk(tuple.getValueNode(), member(path, key.getValue()), startLine(key), out);
        }
      }
    } else if (node instanceof SequenceNode seq) {
      List<Node> items = seq.getValue();
      for (int i = 0; i < items.size(); i++) {
        walk(items.get(i), path + "[" + i + "]", startLine(items.get(i)), out);
      }
    }
  }

  private static void collectRefs(Node node, List<String> out) {
    if (node instanceof MappingNode map) {
      for (NodeTuple tuple : map.getValue()) {
        if (tuple.getKeyNode() instanceof ScalarNode key
            && "$ref".equals(key.getValue())
            && tuple.getValueNode() instanceof ScalarNode target) {
          out.add(target.getValue());
        }
        collectRefs(tuple.getValueNode(), out);
      }
    } else if (node instanceof SequenceNode seq) {
      for (Node item : seq.getValue()) {
        collectRefs(item, out);
      }
    }
  }

  private static int startLine(Node node) {
    return node.getStartMark().getLine() + 1;
  }
}
