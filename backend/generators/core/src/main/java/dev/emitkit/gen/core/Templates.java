package dev.emitkit.gen.core;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mustache templates for one generator, loaded from the classpath at {@code
 * templates/{generator}/{template}.mustache}.
 *
 * <p>HTML escaping is off because the output is source code, not HTML. Missing sections and
 * variables fail loudly rather than rendering as empty text.
 */
public final class Templates {

  private static final String ROOT = "templates/";
  private static final String SUFFIX = ".mustache";

  private static final Mustache.Compiler COMPILER =
      Mustache.compiler().escapeHTML(false).strictSections(true);

  private final String generator;
  private final Map<String, Template> cache = new ConcurrentHashMap<>();

  private Templates(String generator) {
    this.generator = generator;
  }

  /**
   * Returns the templates for {@code name}, read from {@code templates/{name}/} on the classpath.
   */
  public static Templates forGenerator(String name) {
    return new Templates(Objects.requireNonNull(name, "name"));
  }

  /**
   * Renders {@code template} with {@code context}. Line endings in the template are normalised to
   * {@code "\n"} when it is first loaded.
   *
   * @throws IllegalArgumentException if the template is not on the classpath
   * @throws com.samskivert.mustache.MustacheException if a variable or section is missing
   */
  public String render(String template, Object context) {
    Objects.requireNonNull(template, "template");
    return cache.computeIfAbsent(template, this::load).execute(context);
  }

  private Template load(String template) {
    String path = ROOT + generator + "/" + template + SUFFIX;
    InputStream in = Templates.class.getClassLoader().getResourceAsStream(path);
    if (in == null) {
      throw new IllegalArgumentException("Template not found on classpath: " + path);
    }
    try (in) {
      String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return COMPILER.compile(source.replace("\r\n", "\n"));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read template " + path, e);
    }
  }
}
