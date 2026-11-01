package dev.emitkit.spec.naming;

import static dev.emitkit.spec.naming.Language.JAVA;
import static dev.emitkit.spec.naming.Language.PYTHON;
import static dev.emitkit.spec.naming.Language.TYPESCRIPT;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NameStrategyTest {

  @ParameterizedTest
  @CsvSource({
    // raw,          ts.member,   py.member,   java.member, ts.type,   py.file,    java.file
    "user-id,        userId,      user_id,     userId,      UserId,    user_id,    UserId",
    "HTTPServer,     httpServer,  http_server, httpServer,  HttpServer,http_server,HttpServer",
    "list_pets,      listPets,    list_pets,   listPets,    ListPets,  list_pets,  ListPets",
    "2fa,            _2fa,        _2fa,        _2fa,        _2fa,      _2fa,       _2fa"
  })
  void converts(
      String raw,
      String tsMember,
      String pyMember,
      String javaMember,
      String tsType,
      String pyFile,
      String javaFile) {
    var ts = NameStrategy.forLanguage(TYPESCRIPT);
    var py = NameStrategy.forLanguage(PYTHON);
    var java = NameStrategy.forLanguage(JAVA);

    assertThat(ts.member(raw)).isEqualTo(tsMember);
    assertThat(py.member(raw)).isEqualTo(pyMember);
    assertThat(java.member(raw)).isEqualTo(javaMember);
    assertThat(ts.type(raw)).isEqualTo(tsType);
    assertThat(py.file(raw)).isEqualTo(pyFile);
    assertThat(java.file(raw)).isEqualTo(javaFile);
  }

  @Test
  void typeScriptFileStemIsKebabCase() {
    assertThat(NameStrategy.forLanguage(TYPESCRIPT).file("HTTPServer")).isEqualTo("http-server");
  }

  @Test
  void reservedWordsAreEscaped() {
    assertThat(NameStrategy.forLanguage(TYPESCRIPT).member("delete")).isEqualTo("delete_");
    assertThat(NameStrategy.forLanguage(PYTHON).member("class")).isEqualTo("class_");
    assertThat(NameStrategy.forLanguage(JAVA).member("class")).isEqualTo("class_");
    assertThat(NameStrategy.forLanguage(JAVA).type("Object"))
        .isEqualTo("Object_"); // clashes with java.lang
    assertThat(NameStrategy.forLanguage(PYTHON).member("None"))
        .isEqualTo("none"); // snake makes it safe
  }
}
