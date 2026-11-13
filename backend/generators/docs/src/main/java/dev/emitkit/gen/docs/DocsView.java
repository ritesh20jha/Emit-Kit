package dev.emitkit.gen.docs;

import dev.emitkit.gen.core.DocsExamples;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.RequestBody;
import dev.emitkit.spec.model.Response;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Values rendered by {@code tag.md.mustache}: one tag page. Table cells and headings are already
 * escaped, so the template only interpolates.
 *
 * @param apiTitle one-line API title, linking back to the index
 * @param title one-line tag name
 * @param description the tag's description, if it has one
 * @param hasDescription whether {@code description} is shown
 * @param ops the tag's operations in model order
 */
record DocsView(
    String apiTitle, String title, boolean hasDescription, String description, List<Op> ops) {

  record Op(
      String operationId,
      String method,
      String path,
      boolean deprecated,
      boolean hasProse,
      String prose,
      boolean hasSecurity,
      String security,
      boolean hasParams,
      List<Param> params,
      boolean hasBody,
      String contentType,
      String bodyRequired,
      String bodyType,
      boolean hasResponses,
      List<Result> responses,
      String curl,
      String typescript,
      String python,
      String java,
      boolean hasNotes,
      String notes) {}

  record Param(String name, String in, String required, String type, String description) {}

  record Result(String status, String description) {}

  static DocsView of(ApiModel model, DocsGenerator.TagGroup group, Map<String, DocsExamples> ai) {
    String description = group.tag().description();
    boolean hasDescription = description != null && !description.isBlank();
    return new DocsView(
        Markdown.cell(model.info().title()),
        Markdown.cell(group.tag().name()),
        hasDescription,
        hasDescription ? description.strip() : "",
        group.ops().stream().map(op -> op(model, op, ai.get(op.operationId()))).toList());
  }

  private static Op op(ApiModel model, Operation op, DocsExamples override) {
    Examples.Snippets snippets =
        override == null
            ? Examples.render(model, op)
            : new Examples.Snippets(
                override.curl(), override.typescript(), override.python(), override.java());
    String notes = override == null ? "" : override.notes().strip();
    String prose =
        Stream.of(op.summary(), op.description())
            .filter(text -> text != null && !text.isBlank())
            .map(String::strip)
            .distinct()
            .collect(Collectors.joining("\n\n"));
    List<String> security = op.security();
    RequestBody body = op.requestBody();
    return new Op(
        op.operationId(),
        op.method(),
        op.path(),
        op.deprecated(),
        !prose.isEmpty(),
        prose,
        !security.isEmpty(),
        security.stream().map(name -> "`" + name + "`").collect(Collectors.joining(", ")),
        !op.parameters().isEmpty(),
        op.parameters().stream().map(DocsView::param).toList(),
        body != null,
        body == null ? "" : body.contentType(),
        body == null ? "" : yesNo(body.required()),
        body == null ? "" : Schemas.typeText(body.schema()),
        !op.responses().isEmpty(),
        op.responses().values().stream().map(DocsView::result).toList(),
        snippets.curl(),
        snippets.typescript(),
        snippets.python(),
        snippets.java(),
        !notes.isEmpty(),
        notes);
  }

  private static Param param(Parameter p) {
    return new Param(
        Markdown.cell(p.name()),
        Schemas.lower(p.in().name()),
        yesNo(p.required()),
        Markdown.cell(Schemas.typeText(p.schema())),
        Markdown.cell(p.description()));
  }

  private static Result result(Response r) {
    return new Result(Markdown.cell(r.status()), Markdown.cell(r.description()));
  }

  private static String yesNo(boolean value) {
    return value ? "Yes" : "No";
  }
}
