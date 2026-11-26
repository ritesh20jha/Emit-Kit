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
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Refuses a YAML document that aliases would blow up, before swagger-parser expands them. The
 * document is composed without expanding anything, so an alias shares the node it names. Each
 * node's expanded size, height and text are computed once and reused, and the walk stops as soon as
 * a total bound is passed. Nothing here recurses deeper than the depth bound.
 *
 * <p>A document is refused for its whole expansion (more than {@link #MAX_EXPANDED_NODES} nodes or
 * {@link #MAX_DEPTH} levels), and for what its aliases add: a document with no aliases adds
 * nothing, however large it is, while a refused one adds more than {@link #MAX_ALIAS_NODES} nodes
 * or {@link #MAX_ALIAS_CHARS} characters of text beyond its own.
 */
final class AliasBudget {

  /** The bound on alias-expanded nodes, the same as the linter's own walk. */
  static final int MAX_EXPANDED_NODES = 500_000;

  /** The bound on alias-expanded nesting, the same as the linter's own walk. */
  static final int MAX_DEPTH = 256;

  /** The nodes that aliases may add beyond the document's own nodes. */
  static final long MAX_ALIAS_NODES = 10_000;

  /** The text, in characters, that aliases may add beyond the document's own text: 1 MiB. */
  static final long MAX_ALIAS_CHARS = 1L << 20;

  /** Text sums stop growing here, so no sum overflows however far the aliases expand. */
  private static final long CHARS_CAP = 1L << 40;

  private record Measure(int size, int height, long chars) {}

  private final Map<Node, Measure> measured = new IdentityHashMap<>();
  private final Set<Node> open = Collections.newSetFromMap(new IdentityHashMap<>());

  private AliasBudget() {}

  /**
   * Why the document is refused, or null when it is within the bounds. A document that cannot be
   * composed is refused with the reader's message.
   */
  static @Nullable String refusal(String content) {
    // An alias needs an anchor, so a document without '&' has none. Skipping the composition then
    // keeps the common case cheap. A '&' in a string only costs the composition below.
    if (content.indexOf('&') < 0) {
      return null;
    }
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
      AliasBudget budget = new AliasBudget();
      Measure whole = budget.measure(root, 1);
      if (whole.height() > MAX_DEPTH) {
        return nested();
      }
      // Every reachable node was measured once, so the map holds the distinct nodes and their text.
      long distinct = budget.measured.size();
      long ownChars = 0;
      for (Node node : budget.measured.keySet()) {
        if (node instanceof ScalarNode scalar) {
          ownChars += scalar.getValue().length();
        }
      }
      if (whole.size() - distinct > MAX_ALIAS_NODES) {
        return "could not read document: aliases add more than " + MAX_ALIAS_NODES + " nodes";
      }
      if (whole.chars() - ownChars > MAX_ALIAS_CHARS) {
        return "could not read document: aliases add more than 1 MiB of text";
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
    long chars = node instanceof ScalarNode scalar ? scalar.getValue().length() : 0;
    for (Node child : children(node)) {
      Measure m = measure(child, stackDepth + 1);
      size += m.size();
      if (size > MAX_EXPANDED_NODES) {
        throw new Refused(tooLarge());
      }
      chars = Math.min(CHARS_CAP, chars + m.chars());
      height = Math.max(height, m.height());
    }
    open.remove(node);
    Measure result = new Measure(size, height + 1, chars);
    measured.put(node, result);
    return result;
  }

  /** Keys and values of a mapping, and the items of a sequence, one entry each. */
  private static List<Node> children(Node node) {
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
