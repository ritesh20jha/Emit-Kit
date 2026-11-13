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
import org.jspecify.annotations.Nullable;

/**
 * Values rendered by {@code tag.md.mustache}: one tag page. Table cells and headings are already
 * escaped, so the template only interpolates.
 *
 * @param apiTitle one-line API title, linking back to the index
 * @param title one-line tag name
 * @param hasDescription whether {@code description} is shown
 * @param description the tag's description, if it has one
 * @param ops the tag's operations in model order
 */
record DocsView(
    String apiTitle, String title, boolean hasDescription, String description, List<Op> ops) {

  /** Text for a table cell or heading: one line, with pipes escaped. Null becomes empty. */
  static String cell(@Nullable String text) {
    return text == null ? "" : text.replaceAll("\\s+", " ").strip().replace("|", "\\|");
  }

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
      boolean hasBodyFields,
      List<Schemas.Field> bodyFields,
      boolean hasResponses,
      List<Result> responses,
      List<FieldTable> fieldTables,
      String curl,
      String typescript,
      String python,
      String java,
      boolean hasNotes,
      String notes) {}

  record Param(String name, String in, String type, String required, String description) {}

  /** A response row: its status, description and schema label. */
  record Result(String status, String description, String schema) {}

  /** The property table of one response schema, shown only when it has properties. */
  record FieldTable(String status, List<Schemas.Field> fields) {}

  static DocsView of(ApiModel model, DocsGenerator.TagGroup group, Map<String, DocsExamples> ai) {
    String description = group.tag().description();
    boolean hasDescription = description != null && !description.isBlank();
    return new DocsView(
        DocsView.cell(model.info().title()),
        DocsView.cell(group.tag().name()),
        hasDescription,
        hasDescription ? description.strip() : "",
        group.ops().stream().map(op -> op(model, op, ai.get(op.operationId()))).toList());
  }

  private static Op op(ApiModel model, Operation op, DocsExamples override) {
    DocsExamples examples = override != null ? override : Examples.render(model, op);
    String notes = override == null ? "" : override.notes().strip();
    String prose =
        Stream.of(op.summary(), op.description())
            .filter(text -> text != null && !text.isBlank())
            .map(String::strip)
            .distinct()
            .collect(Collectors.joining("\n\n"));
    List<String> security = op.security();
    RequestBody body = op.requestBody();
    List<Schemas.Field> bodyFields =
        body == null ? List.of() : Schemas.fields(body.schema(), model.schemas());
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
        body == null ? "" : Schemas.yesNo(body.required()),
        body == null ? "" : Schemas.typeText(body.schema()),
        !bodyFields.isEmpty(),
        bodyFields,
        !op.responses().isEmpty(),
        op.responses().values().stream().map(DocsView::result).toList(),
        fieldTables(model, op),
        examples.curl(),
        examples.typescript(),
        examples.python(),
        examples.java(),
        !notes.isEmpty(),
        notes);
  }

  private static Param param(Parameter p) {
    return new Param(
        DocsView.cell(p.name()),
        Schemas.lower(p.in().name()),
        DocsView.cell(Schemas.typeText(p.schema())),
        Schemas.yesNo(p.required()),
        DocsView.cell(p.description()));
  }

  private static Result result(Response r) {
    return new Result(
        DocsView.cell(r.status()),
        DocsView.cell(r.description()),
        r.schema() == null ? "" : DocsView.cell(Schemas.typeText(r.schema())));
  }

  private static List<FieldTable> fieldTables(ApiModel model, Operation op) {
    return op.responses().values().stream()
        .filter(r -> r.schema() != null)
        .map(r ->
            new FieldTable(DocsView.cell(r.status()), Schemas.fields(r.schema(), model.schemas())))
        .filter(table -> !table.fields().isEmpty())
        .toList();
  }
}
