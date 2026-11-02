package dev.emitkit.spec.parse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Info;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.Server;
import dev.emitkit.spec.model.Tag;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.converter.SwaggerConverter;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Parses OpenAPI 2.0, 3.0 and 3.1 documents (YAML or JSON) into the {@link ApiModel}. */
public final class SpecParser {

  private static final ParseOptions OPTIONS = options();

  /** Never throws for bad input: problems are reported in {@link ParseResult#errors()}. */
  public ParseResult parse(String content) {
    Objects.requireNonNull(content, "content");
    String hash = sha256Hex(content);
    String format = content.stripLeading().startsWith("{") ? "json" : "yaml";
    try {
      SwaggerParseResult swagger = read(content);
      List<String> errors = new ArrayList<>(swagger.getMessages());
      OpenAPI api = swagger.getOpenAPI();
      if (api == null) {
        if (errors.isEmpty()) {
          errors.add("document could not be parsed");
        }
        return new ParseResult(null, errors, null, hash, format);
      }
      if (api.getInfo() == null
          || api.getInfo().getTitle() == null
          || api.getInfo().getVersion() == null) {
        errors.add("info.title and info.version are required");
        return new ParseResult(null, errors, null, hash, format);
      }
      return new ParseResult(toModel(api), errors, api.getInfo().getVersion(), hash, format);
    } catch (RuntimeException e) {
      return new ParseResult(
          null, List.of("could not parse document: " + e.getMessage()), null, hash, format);
    }
  }

  /** Swagger 2.0 goes through the v2 converter; 3.x goes straight to the v3 parser. */
  private static SwaggerParseResult read(String content) {
    return isSwagger2(content)
        ? new SwaggerConverter().readContents(content, null, OPTIONS)
        : new OpenAPIV3Parser().readContents(content, null, OPTIONS);
  }

  private static boolean isSwagger2(String content) {
    try {
      JsonNode root = Yaml.mapper().readTree(content);
      return root != null && root.has("swagger");
    } catch (JsonProcessingException e) {
      // Unreadable document: the v3 parser reports the problem.
      return false;
    }
  }

  private static ParseOptions options() {
    var options = new ParseOptions();
    options.setResolve(true);
    options.setResolveFully(false);
    return options;
  }

  private static ApiModel toModel(OpenAPI api) {
    var info = api.getInfo();
    String contactEmail = info.getContact() == null ? null : info.getContact().getEmail();
    return new ApiModel(
        new Info(info.getTitle(), info.getVersion(), info.getDescription(), contactEmail),
        servers(api),
        securitySchemes(api),
        tags(api),
        OperationMapper.map(api),
        Map.of());
  }

  private static List<Server> servers(OpenAPI api) {
    var out = new ArrayList<Server>();
    if (api.getServers() != null) {
      for (var server : api.getServers()) {
        out.add(new Server(server.getUrl(), server.getDescription()));
      }
    }
    return out;
  }

  private static List<Tag> tags(OpenAPI api) {
    var out = new ArrayList<Tag>();
    if (api.getTags() != null) {
      for (var tag : api.getTags()) {
        out.add(new Tag(tag.getName(), tag.getDescription()));
      }
    }
    return out;
  }

  private static Map<String, SecurityScheme> securitySchemes(OpenAPI api) {
    var out = new LinkedHashMap<String, SecurityScheme>();
    if (api.getComponents() == null || api.getComponents().getSecuritySchemes() == null) {
      return out;
    }
    for (var entry : api.getComponents().getSecuritySchemes().entrySet()) {
      var scheme = entry.getValue();
      var kind = kind(scheme.getType());
      if (kind != null) {
        String in = scheme.getIn() == null ? null : scheme.getIn().toString();
        out.put(
            entry.getKey(),
            new SecurityScheme(entry.getKey(), kind, in, scheme.getName(), scheme.getScheme()));
      }
    }
    return out;
  }

  private static SecurityScheme.@Nullable Kind kind(
      io.swagger.v3.oas.models.security.SecurityScheme.@Nullable Type type) {
    if (type == null) {
      return null;
    }
    return switch (type) {
      case APIKEY -> SecurityScheme.Kind.API_KEY;
      case HTTP -> SecurityScheme.Kind.HTTP;
      case OAUTH2 -> SecurityScheme.Kind.OAUTH2;
      case OPENIDCONNECT -> SecurityScheme.Kind.OPENID_CONNECT;
      default -> null;
    };
  }

  private static String sha256Hex(String content) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
