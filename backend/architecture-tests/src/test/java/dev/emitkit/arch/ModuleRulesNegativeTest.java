package dev.emitkit.arch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ModuleRulesNegativeTest {
  @Test
  void commonDependingOnApi_isRejected() {
    JavaClasses classes = new ClassFileImporter().importPackages("fixtures.bad");
    ArchRule rule = ModuleRules.commonIsIndependent("fixtures.bad");
    assertThatThrownBy(() -> rule.check(classes))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("BadCommon");
  }
}
