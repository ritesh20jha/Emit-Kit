package dev.emitkit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.core.util.Separators.Spacing;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the served OpenAPI document to frontend/openapi.json, the input of the generated TypeScript
 * client. Run with -Dopenapi.update=true (see scripts/export-openapi.sh) to rewrite the file
 * instead of comparing. Both paths use the same normalizer, so the export and the check cannot
 * drift.
 */
@SpringBootTest(
    properties = {
      "emitkit.cors.allowed-origins=http://localhost:5173",
      "emitkit.vault.cipher=local"
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class OpenApiSnapshotTest {

  /** Gradle runs tests in backend/api-app, so this resolves to the repository's frontend folder. */
  private static final Path COMMITTED = Path.of("../../frontend/openapi.json");

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  /** Two-space indent, "key": value, one array element per line, LF line ends on every OS. */
  private static final DefaultPrettyPrinter PRINTER =
      new DefaultPrettyPrinter()
          .withObjectIndenter(new DefaultIndenter("  ", "\n"))
          .withArrayIndenter(new DefaultIndenter("  ", "\n"))
          .withSeparators(
              Separators.createDefaultInstance().withObjectNameValueSpacing(Spacing.AFTER));

  @Autowired private MockMvc mvc;

  @Test
  void apiDocsMatchCommittedFile() throws Exception {
    String served =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String normalized = normalize(served);

    if (Boolean.getBoolean("openapi.update")) {
      Files.writeString(COMMITTED, normalized);
      return;
    }
    assertThat(normalized).isEqualTo(Files.readString(COMMITTED));
  }

  /** Sorted keys at every depth, two-space indent, trailing newline: one canonical text. */
  static String normalize(String json) {
    Object tree = MAPPER.readValue(json, Object.class);
    return MAPPER.writer().with(PRINTER).writeValueAsString(tree) + "\n";
  }
}
