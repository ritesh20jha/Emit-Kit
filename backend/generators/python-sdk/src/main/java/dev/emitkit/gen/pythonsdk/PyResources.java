package dev.emitkit.gen.pythonsdk;

import dev.emitkit.gen.core.FileKind;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.gen.core.Templates;
import dev.emitkit.gen.pythonsdk.PyClientView.Literal;
import dev.emitkit.gen.pythonsdk.PyTypeRenderer.Scope;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.Schema;
import dev.emitkit.spec.naming.Language;
import dev.emitkit.spec.naming.NameStrategy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The resource modules, {@code {pkg}/resources/{tag}.py}: one module per tag, each with a sync
 * {@code {Tag}Resource} and an {@code Async{Tag}Resource} holding one method per operation. Path
 * and query parameters and the request body are keyword-only arguments; headers and cookies are not
 * sent.
 */
final class PyResources {

  /** Argument names the method body needs: a parameter of one of these would shadow them. */
  private static final Set<String> ARG_SHADOWS = Set.of("list", "response", "self", "str");

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
  private static final Set<String> TYPING = Set.of("Any", "Optional", "Union");
  private static final Set<String> PYDANTIC = Set.of("Field", "TypeAdapter");

  private PyResources() {}

  /** One method, sync or async. {@code def} is {@code "def "} or {@code "async def "}. */
  record Method(
      String def,
      String name,
      String signature,
      String returnType,
      boolean hasDoc,
      String doc,
      List<Literal> lines) {}

  /** A resource module as the template reads it. */
  record ResourceModule(
      String classDoc,
      String className,
      String asyncClassName,
      String clientClass,
      String asyncClientClass,
      String imports,
      boolean hasDropNone,
      List<Method> syncMethods,
      List<Method> asyncMethods) {}

  /** A method before its sync or async form is chosen. */
  private record Plan(
      String name,
      List<String> args,
      String returnType,
      String adapter,
      String doc,
      List<String> request) {}

  static List<GeneratedFile> files(
      ApiModel model, PyTypeRenderer types, PyClientView view, String pkg, Templates templates) {
    NameStrategy names = NameStrategy.forLanguage(Language.PYTHON);
    Map<String, List<Operation>> byModule = new LinkedHashMap<>();
    for (Operation op : model.operations()) {
      byModule.computeIfAbsent(names.file(op.tag()), k -> new ArrayList<>()).add(op);
    }
    List<GeneratedFile> files = new ArrayList<>();
    for (var entry : byModule.entrySet()) {
      ResourceModule module = module(entry.getValue(), types, view, names);
      files.add(
          new GeneratedFile(
              pkg + "resources/" + entry.getKey() + ".py",
              templates.render("resource.py", module),
              FileKind.GENERATED));
    }
    return files;
  }

  /** Operations are grouped by module name, so tags such as "user" and "User" share one module. */
  private static ResourceModule module(
      List<Operation> ops, PyTypeRenderer types, PyClientView view, NameStrategy names) {
    String tag = ops.get(0).tag();
    String className = names.type(tag) + "Resource";
    Set<String> imports = new TreeSet<>();
    Scope scope = Scope.complete(imports);
    Set<String> methodNames = new HashSet<>();
    List<Plan> plans = new ArrayList<>();
    for (Operation op : ops) {
      plans.add(plan(op, types, names, scope, methodNames));
    }
    return new ResourceModule(
        PyClientView.docText(tag),
        className,
        "Async" + className,
        view.className(),
        view.asyncClassName(),
        imports(scope, view),
        imports.contains("_drop_none"),
        plans.stream().map(p -> method(p, false)).toList(),
        plans.stream().map(p -> method(p, true)).toList());
  }

  private static Plan plan(
      Operation op,
      PyTypeRenderer types,
      NameStrategy names,
      Scope scope,
      Set<String> methodNames) {
    Set<String> argNames = new HashSet<>(ARG_SHADOWS);
    List<String> args = new ArrayList<>();
    Map<String, String> pathNames = new HashMap<>();
    List<String> query = new ArrayList<>();
    for (Parameter p : op.parameters()) {
      if (p.in() != ParamLocation.PATH && p.in() != ParamLocation.QUERY) {
        continue;
      }
      String py = PyTypeRenderer.unique(names.member(p.name()), argNames);
      String type = types.typeOf(p.schema(), PyTypeRenderer.hint(op, p.name()), scope);
      if (p.in() == ParamLocation.PATH) {
        pathNames.put(p.name(), py);
        args.add(py + ": " + type);
      } else {
        query.add(PyClientView.quote(p.name()) + ": " + py);
        args.add(keywordArg(py, type, p.required(), p.schema(), scope));
      }
    }
    List<String> request = new ArrayList<>();
    request.add(PyClientView.quote(op.method().toUpperCase(Locale.ROOT)));
    request.add(pathExpr(op.path(), pathNames, scope));
    if (!query.isEmpty()) {
      scope.use("Any");
      request.add("query=" + scope.use("_drop_none") + "({" + String.join(", ", query) + "})");
    }
    if (op.requestBody() != null) {
      String py = PyTypeRenderer.unique("body", argNames);
      Schema schema = op.requestBody().schema();
      String type = types.typeOf(schema, PyTypeRenderer.hint(op, "request"), scope);
      boolean required = op.requestBody().required();
      args.add(keywordArg(py, type, required, schema, scope));
      String dump =
          scope.use("TypeAdapter")
              + "("
              + type
              + ").dump_python("
              + py
              + ", mode=\"json\", by_alias=True, exclude_unset=True)";
      request.add("json_body=" + (required ? dump : "None if " + py + " is None else " + dump));
    }
    Schema response = PyTypeRenderer.successSchema(op);
    String returnType =
        response == null
            ? "None"
            : types.typeOf(response, PyTypeRenderer.hint(op, "response"), scope);
    String adapter = response == null ? null : scope.use("TypeAdapter") + "(" + returnType + ")";
    String doc = op.summary() == null ? "" : PyClientView.docText(op.summary());
    String name = PyTypeRenderer.unique(names.member(op.operationId()), methodNames);
    return new Plan(name, args, returnType, adapter, doc, request);
  }

  /** A keyword-only argument: required, or defaulting to None with an Optional type. */
  private static String keywordArg(
      String name, String type, boolean required, Schema schema, Scope scope) {
    if (required) {
      return name + ": " + type;
    }
    String optional = schema.nullable() ? type : PyTypeRenderer.wrapOptional(type, scope);
    return name + ": " + optional + " = None";
  }

  private static Method method(Plan plan, boolean async) {
    String call =
        (async ? "await " : "")
            + "self._client._request("
            + String.join(", ", plan.request())
            + ")";
    List<String> body = new ArrayList<>();
    if (plan.adapter() == null) {
      body.add(call);
    } else {
      body.add("response = " + call);
      body.add("return " + plan.adapter() + ".validate_python(response.json())");
    }
    List<Literal> lines = body.stream().map(Literal::new).toList();
    String signature =
        "self" + (plan.args().isEmpty() ? "" : ", *, " + String.join(", ", plan.args()));
    return new Method(
        async ? "async def " : "def ",
        plan.name(),
        signature,
        plan.returnType(),
        !plan.doc().isEmpty(),
        plan.doc(),
        lines);
  }

  /**
   * A Python expression for the request path. Placeholders become URL-encoded arguments; text is
   * escaped for an f-string when there is an interpolation and for a plain string otherwise.
   */
  static String pathExpr(String path, Map<String, String> pyNames, Scope scope) {
    Matcher m = PLACEHOLDER.matcher(path);
    StringBuilder fstring = new StringBuilder();
    boolean interpolated = false;
    int last = 0;
    while (m.find()) {
      fstring.append(fstringText(path.substring(last, m.start())));
      String py = pyNames.get(m.group(1));
      if (py == null) {
        fstring.append(fstringText(m.group()));
      } else {
        interpolated = true;
        scope.use("quote");
        fstring.append("{_quote(str(").append(py).append("), safe='')}");
      }
      last = m.end();
    }
    fstring.append(fstringText(path.substring(last)));
    if (!interpolated) {
      return PyClientView.quote(path);
    }
    return "f\"" + fstring + "\"";
  }

  /** {@code text} escaped for the inside of a double-quoted f-string. */
  private static String fstringText(String text) {
    String quoted = PyClientView.quote(text);
    return quoted.substring(1, quoted.length() - 1).replace("{", "{{").replace("}", "}}");
  }

  private static String imports(Scope scope, PyClientView view) {
    List<String> stdlib = new ArrayList<>();
    List<String> typing = new ArrayList<>(List.of("TYPE_CHECKING"));
    typing.addAll(scope.imports.stream().filter(TYPING::contains).sorted().toList());
    stdlib.add("from typing import " + String.join(", ", typing));
    if (scope.imports.contains("quote")) {
      stdlib.add("from urllib.parse import quote as _quote");
    }
    List<String> pydantic = scope.imports.stream().filter(PYDANTIC::contains).toList();
    List<String> groups = new ArrayList<>();
    groups.add(String.join("\n", stdlib));
    if (!pydantic.isEmpty()) {
      groups.add("from pydantic import " + String.join(", ", pydantic));
    }
    if (!scope.names.isEmpty()) {
      groups.add("from ..models import " + String.join(", ", new TreeSet<>(scope.names)));
    }
    groups.add(
        "if TYPE_CHECKING:\n    from ..client import "
            + view.className()
            + ", "
            + view.asyncClassName());
    return String.join("\n\n", groups);
  }
}
