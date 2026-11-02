package dev.emitkit.gen.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samskivert.mustache.MustacheException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplatesTest {

  private final Templates t = Templates.forGenerator("dummy");

  @Test
  void rendersWithoutHtmlEscaping() {
    assertThat(t.render("hello", Map.of("name", "<A&B>"))).isEqualTo("Hello <A&B>\n");
  }

  @Test
  void missingVariableFails() {
    assertThatThrownBy(() -> t.render("hello", Map.of())).isInstanceOf(MustacheException.class);
  }

  @Test
  void missingTemplateFails() {
    assertThatThrownBy(() -> t.render("nope", Map.of()))
        .hasMessageContaining("templates/dummy/nope.mustache");
  }
}
