package dev.emitkit.gen.docs;

import dev.emitkit.gen.core.DocsExamples;
import dev.emitkit.gen.core.FileKind;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.gen.core.Generator;
import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.GeneratorId;
import dev.emitkit.gen.core.Templates;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.Tag;
import dev.emitkit.spec.naming.Language;
import dev.emitkit.spec.naming.NameStrategy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Emits Markdown API docs under {@code {outputDir}docs/v{version}/}: an {@code index.md} and one
 * page per tag. Each operation has its parameters, request body, responses and code examples.
 *
 * <p>Output is byte-stable: tags keep model order and no timestamps are written. An AI assistant,
 * when supplied, is asked once per tag; if it fails, the template examples are used.
 */
public final class DocsGenerator implements Generator {

  private static final Templates TEMPLATES = Templates.forGenerator("docs");

  /** Page file stems are kebab-case. */
  private static final NameStrategy STEMS = NameStrategy.forLanguage(Language.TYPESCRIPT);

  /** One tag page: its tag, file name and operations in model order. */
  record TagGroup(Tag tag, String file, List<Operation> ops) {}

  @Override
  public GeneratorId id() {
    return GeneratorId.DOCS;
  }

  @Override
  public List<GeneratedFile> generate(ApiModel model, GeneratorContext ctx) {
    String root = docsRoot(ctx.outputDir(), ctx.version());
    List<TagGroup> groups = groups(model);
    var files = new ArrayList<GeneratedFile>();
    files.add(generated(root + "index.md", "index.md", IndexView.of(model, groups)));
    for (TagGroup group : groups) {
      var view = DocsView.of(model, group, aiExamples(ctx, group));
      files.add(generated(root + group.file(), "tag.md", view));
    }
    return List.copyOf(files);
  }

  /**
   * Operations grouped by their tag: declared tags with operations first, in declaration order,
   * then undeclared tags in first-seen order.
   */
  static List<TagGroup> groups(ApiModel model) {
    Map<String, Tag> declared = new LinkedHashMap<>();
    for (Tag tag : model.tags()) {
      declared.putIfAbsent(tag.name(), tag);
    }
    Map<String, List<Operation>> byTag = new LinkedHashMap<>();
    declared.keySet().forEach(name -> byTag.put(name, new ArrayList<>()));
    for (Operation op : model.operations()) {
      byTag.computeIfAbsent(op.tag(), name -> new ArrayList<>()).add(op);
    }
    Set<String> stems = new HashSet<>(Set.of("index"));
    var groups = new ArrayList<TagGroup>();
    byTag.forEach((name, ops) -> {
      if (!ops.isEmpty()) {
        Tag tag = declared.getOrDefault(name, new Tag(name, null));
        groups.add(new TagGroup(tag, uniqueStem(name, stems) + ".md", ops));
      }
    });
    return List.copyOf(groups);
  }

  /** The tag's file stem, with {@code -2}, {@code -3}, ... appended while it is taken. */
  private static String uniqueStem(String tag, Set<String> taken) {
    String base = STEMS.file(tag);
    String stem = base;
    for (int n = 2; !taken.add(stem); n++) {
      stem = base + "-" + n;
    }
    return stem;
  }

  /**
   * Examples the AI assistant wrote for this tag, keyed by operationId. Empty when there is no
   * assistant, or when it throws: the templates then supply every example.
   */
  private static Map<String, DocsExamples> aiExamples(GeneratorContext ctx, TagGroup group) {
    if (ctx.ai().isEmpty()) {
      return Map.of();
    }
    try {
      @Nullable Map<String, DocsExamples> found = ctx.ai().get().examples(group.tag(), group.ops());
      return found == null ? Map.of() : found;
    } catch (RuntimeException e) {
      return Map.of();
    }
  }

  /** {@code outputDir} with trailing slashes normalised to exactly one, then {@code docs/v}. */
  static String docsRoot(String outputDir, String version) {
    String base = outputDir.replaceAll("/+$", "");
    return (base.isEmpty() ? "" : base + "/") + "docs/v" + version + "/";
  }

  private static GeneratedFile generated(String path, String template, Object view) {
    return new GeneratedFile(path, TEMPLATES.render(template, view), FileKind.GENERATED);
  }
}
