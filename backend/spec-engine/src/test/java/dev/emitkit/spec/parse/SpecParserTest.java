package dev.emitkit.spec.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SpecParserTest {

  private static final String HEADER =
      "openapi: 3.0.3\ninfo: {title: T, version: \"1\"}\npaths: {}\n";

  private static final String BOUNDED = "it has more than 500000 nodes once aliases are expanded";

  private final SpecParser parser = new SpecParser();

  /**
   * Each level holds two aliases of the level below, so {@code levels} levels expand to 2^levels
   * leaves.
   */
  private static String aliasLadder(int levels) {
    var yaml = new StringBuilder(HEADER + "x-l0: &l0 {v: 1}\n");
    for (int i = 1; i <= levels; i++) {
      yaml.append("x-l%d: &l%d {a: *l%d, b: *l%d}\n".formatted(i, i, i - 1, i - 1));
    }
    return yaml.toString();
  }

  @Test
  void aliasLadder20_isRejectedAsAParseErrorWithoutExpansion() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          ParseResult r = parser.parse(aliasLadder(20));
          assertThat(r.model()).isNull();
          assertThat(r.errors()).containsExactly("could not read document: " + BOUNDED);
        });
  }

  @Test
  void aliasLadder24_isRejectedAsAParseErrorWithoutExpansion() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          ParseResult r = parser.parse(aliasLadder(24));
          assertThat(r.model()).isNull();
          assertThat(r.errors()).containsExactly("could not read document: " + BOUNDED);
        });
  }

  /** A server list whose items are all aliases of one mapping with a URL of {@code urlLength}. */
  private static String aliasedServers(int aliases, int urlLength) {
    var yaml = new StringBuilder(HEADER + "servers:\n");
    yaml.append("  - &s {url: \"http://").append("a".repeat(urlLength)).append("\"}\n");
    for (int i = 0; i < aliases; i++) {
      yaml.append("  - *s\n");
    }
    return yaml.toString();
  }

  @Test
  void aliasesAddingManyNodes_areRefusedQuickly() {
    String raw = aliasedServers(40_000, 1024);
    assertTimeoutPreemptively(
        Duration.ofSeconds(1),
        () -> {
          ParseResult r = parser.parse(raw);
          assertThat(r.model()).isNull();
          assertThat(r.errors())
              .containsExactly("could not read document: aliases add more than 10000 nodes");
        });
  }

  @Test
  void aliasesAddingManyTextChars_areRefused() {
    String big = "b".repeat(64 * 1024);
    var yaml = new StringBuilder(HEADER + "x-big: &big \"" + big + "\"\n");
    for (int i = 0; i < 300; i++) {
      yaml.append("x-s%d: *big\n".formatted(i));
    }
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          ParseResult r = parser.parse(yaml.toString());
          assertThat(r.model()).isNull();
          assertThat(r.errors())
              .containsExactly("could not read document: aliases add more than 1 MiB of text");
        });
  }

  @Test
  void aliasHeavyDocumentWithLargeText_isRefusedWithoutExpanding() {
    // 165,000 aliases of a 64 KiB URL: once expanded, about 10 GB of text.
    String raw = aliasedServers(165_000, 64 * 1024);
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          ParseResult r = parser.parse(raw);
          assertThat(r.model()).isNull();
          assertThat(r.errors())
              .containsExactly("could not read document: aliases add more than 10000 nodes");
        });
  }

  @Test
  void largeAliasFreeDocument_isNotRefused() {
    var yaml = new StringBuilder(HEADER + "x-unused: &unused \"v\"\n");
    String value = "t".repeat(512);
    for (int i = 0; i < 2_000; i++) {
      yaml.append("x-k%d: \"%s\"\n".formatted(i, value));
    }
    assertTimeoutPreemptively(
        Duration.ofSeconds(5), () -> assertThat(AliasBudget.refusal(yaml.toString())).isNull());
  }

  @Test
  void documentWithoutAnchors_skipsTheAliasCheckAndStillReportsErrors() {
    String raw = "openapi: [unclosed";
    assertThat(AliasBudget.refusal(raw)).isNull();
    ParseResult r = parser.parse(raw);
    assertThat(r.model()).isNull();
    assertThat(r.errors()).singleElement().asString().startsWith("could not read document: ");
  }

  @Test
  void ordinarySpecWithAFewAliases_stillParses() {
    String raw =
        """
        openapi: 3.0.3
        info: {title: T, version: "1"}
        paths: {}
        components:
          schemas:
            Name: &text {type: string, maxLength: 80}
            Title: *text
            Label: *text
            Note:
              type: object
              properties:
                body: *text
        """;
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          ParseResult r = parser.parse(raw);
          assertThat(r.errors()).isEmpty();
          assertThat(r.model().schemas()).containsOnlyKeys("Name", "Title", "Label", "Note");
        });
  }
}
