package dev.emitkit.spec.parse;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Refuses a YAML document whose aliases would expand past a bound, before swagger-parser expands
 * them. The document is composed without expanding anything, so an alias shares the node it names.
 * Each node's expanded size and height are computed once and reused, and the walk stops as soon as
 * a bound is passed. Nothing here recurses deeper than the depth bound.
 */
final class AliasBudget {

  /** The bound on alias-expanded nodes, the same as the linter's own walk. */
  static final int MAX_EXPANDED_NODES = 500_000;

  /** The bound on alias-expanded nesting, the same as the linter's own walk. */
  static final int MAX_DEPTH = 256;

  private record Measure(int size, int height) {}

  private final Map<Node, Measure> measured = new IdentityHashMap<>();
  private final Set<Node> open = Collections.newSetFromMap(new IdentityHashMap<>());

  private AliasBudget() {}

  /**
   * Why the document is refused, or null when it is within the bounds. A document that cannot be
   * composed is refused with the reader's message.
   */
  static @Nullable String refusal(String content) {
    LoaderOptions options = new LoaderOptions();
    // The aliases are counted below, not limited by SnakeYAML, which would refuse ordinary specs.
    options.setMaxAliasesForCollections(Integer.MAX_VALUE);
    Node root;
    try {
      root = new Yaml(options).compose(new StringReader(content));
    } catch (YAMLException e) {
      return "could not read document: " + e.getMessage();
    }
    if (root == null) {
      return null;
    }
    try {
      Measure whole = new AliasBudget().measure(root, 1);
      if (whole.height() > MAX_DEPTH) {
        return nested();
      }
      return null;
    } catch (Refused e) {
      return e.getMessage();
    }
  }

  private Measure measure(Node node, int stackDepth) {
    if (stackDepth > MAX_DEPTH) {
      throw new Refused(nested());
    }
    Measure known = measured.get(node);
    if (known != null) {
      return known;
    }
    if (!open.add(node)) {
      throw new Refused("could not read document: it contains a recursive alias");
    }
    int size = 1;
    int height = 0;
    for (Node child : children(node)) {
      Measure m = measure(child, stackDepth + 1);
      size += m.size();
      if (size > MAX_EXPANDED_NODES) {
        throw new Refused(tooLarge());
      }
      height = Math.max(height, m.height());
    }
    open.remove(node);
    Measure result = new Measure(size, height + 1);
    measured.put(node, result);
    return result;
  }

  /** Keys and values of a mapping, and the items of a sequence, one entry each. */
  private static Iterable<Node> children(Node node) {
    if (node instanceof MappingNode map) {
      var out = new ArrayList<Node>();
      for (NodeTuple tuple : map.getValue()) {
        out.add(tuple.getKeyNode());
        out.add(tuple.getValueNode());
      }
      return out;
    }
    if (node instanceof SequenceNode seq) {
      return seq.getValue();
    }
    return List.of();
  }

  private static String tooLarge() {
    return "could not read document: it has more than "
        + MAX_EXPANDED_NODES
        + " nodes once aliases are expanded";
  }

  private static String nested() {
    return "could not read document: it is nested more than "
        + MAX_DEPTH
        + " levels deep once aliases are expanded";
  }

  /** A refusal found while walking; it carries the message for the parse error. */
  private static final class Refused extends RuntimeException {
    Refused(String message) {
      super(message, null, false, false);
    }
  }
}
