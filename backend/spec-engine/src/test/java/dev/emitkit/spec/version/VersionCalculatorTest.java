package dev.emitkit.spec.version;

import static dev.emitkit.spec.version.VersionStrategy.EMITKIT_MANAGED;
import static dev.emitkit.spec.version.VersionStrategy.SPEC_VERSION;
import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.spec.diff.Change;
import dev.emitkit.spec.diff.ChangeKind;
import dev.emitkit.spec.diff.OpChange;
import dev.emitkit.spec.diff.OpRef;
import dev.emitkit.spec.diff.SpecDiff;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VersionCalculatorTest {

  private static final String IGNORED_INFO_VERSION = "9.9.9";
  private static final SpecDiff ANY_DIFF = diffOf("added");

  private final VersionCalculator calc = new VersionCalculator();

  @ParameterizedTest(name = "{0} + {1} -> {2}")
  @CsvSource(
      nullValues = "-",
      value = {
        // last,  diff,      expected, changeType
        "-,       added,     0.1.0,    MINOR",
        "0.4.2,   breaking,  0.5.0,    MINOR",
        "0.4.2,   removed,   0.5.0,    MINOR",
        "0.4.2,   added,     0.4.3,    PATCH",
        "0.4.2,   modified,  0.4.3,    PATCH",
        "1.2.3,   breaking,  2.0.0,    MAJOR",
        "1.2.3,   added,     1.3.0,    MINOR",
        "1.2.3,   none,      1.2.3,    -"
      })
  void emitkitManaged(String last, String diffKind, String expected, ChangeType type) {
    VersionResult result =
        calc.next(
            EMITKIT_MANAGED, Optional.ofNullable(last), diffOf(diffKind), IGNORED_INFO_VERSION);

    assertThat(result.version()).isEqualTo(expected);
    assertThat(result.changeType()).isEqualTo(Optional.ofNullable(type));
  }

  @Test
  void emitkitManagedUnparseableLastVersionIsFirstRun() {
    VersionResult result =
        calc.next(EMITKIT_MANAGED, Optional.of("abc"), ANY_DIFF, IGNORED_INFO_VERSION);

    assertThat(result).isEqualTo(new VersionResult("0.1.0", Optional.of(ChangeType.MINOR)));
  }

  @Test
  void specVersion_usesInfoVersion() {
    assertThat(calc.next(SPEC_VERSION, Optional.of("1.0.0"), ANY_DIFF, "3.4.5").version())
        .isEqualTo("3.4.5");
  }

  @Test
  void specVersion_invalidSemver_defaults() {
    assertThat(calc.next(SPEC_VERSION, Optional.empty(), ANY_DIFF, "abc").version())
        .isEqualTo("0.1.0");
  }

  @ParameterizedTest(name = "{0} -> {1} = {2} ({3})")
  @CsvSource(
      nullValues = "-",
      value = {
        // last,        info,         expected,     changeType
        "-,             1.0.0,        1.0.0,        MINOR",
        "-,             abc,          0.1.0,        MINOR",
        "abc,           1.2.0,        1.2.0,        MINOR",
        "1.0.0,         1.0.0,        1.0.0,        -",
        "1.0.0,         2.0.0,        2.0.0,        MAJOR",
        "1.0.0,         1.3.0,        1.3.0,        MINOR",
        "1.0.0,         1.0.4,        1.0.4,        PATCH",
        "1.0.0-rc.1,    1.0.0,        1.0.0,        PATCH",
        "1.0.0,         1.0.0+build.5, 1.0.0+build.5, PATCH"
      })
  void specVersion_changeType(String last, String info, String expected, ChangeType type) {
    VersionResult result = calc.next(SPEC_VERSION, Optional.ofNullable(last), ANY_DIFF, info);

    assertThat(result.version()).isEqualTo(expected);
    assertThat(result.changeType()).isEqualTo(Optional.ofNullable(type));
  }

  private static SpecDiff diffOf(String kind) {
    OpRef op = new OpRef("listPets", "get", "/pets");
    return switch (kind) {
      case "none" -> SpecDiff.empty();
      case "added" -> new SpecDiff(List.of(op), List.of(), List.of());
      case "removed" -> new SpecDiff(List.of(), List.of(op), List.of());
      case "modified" -> modified(op, ChangeKind.DESCRIPTION_CHANGED, false);
      case "breaking" -> modified(op, ChangeKind.TYPE_CHANGED, true);
      default -> throw new IllegalArgumentException(kind);
    };
  }

  private static SpecDiff modified(OpRef op, ChangeKind kind, boolean breaking) {
    Change change = new Change(kind, "body.id", breaking);
    return new SpecDiff(List.of(), List.of(), List.of(new OpChange(op, List.of(change))));
  }
}
