package dev.emitkit.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "dev.emitkit")
class ModuleDependencyRulesTest {
  private static final String ROOT = "dev.emitkit";

  @ArchTest static final ArchRule commonIsIndependent = ModuleRules.commonIsIndependent(ROOT);

  @ArchTest static final ArchRule domainOnlyUsesCommon = ModuleRules.domainOnlyUsesCommon(ROOT);

  @ArchTest static final ArchRule specHasNoSpring = ModuleRules.specHasNoSpring(ROOT);

  @ArchTest
  static final ArchRule generatorsUseOnlySpecAndCommon =
      ModuleRules.generatorsUseOnlySpecAndCommon(ROOT);

  @ArchTest static final ArchRule generatorsDoNoIo = ModuleRules.generatorsDoNoIo(ROOT);

  @ArchTest static final ArchRule vcsOnlyUsesCommon = ModuleRules.vcsOnlyUsesCommon(ROOT);

  @ArchTest static final ArchRule aiDoesNotUseApps = ModuleRules.aiDoesNotUseApps(ROOT);

  @ArchTest static final ArchRule nothingDependsOnApiApp = ModuleRules.nothingDependsOnApiApp(ROOT);

  @ArchTest static final ArchRule workerDoesNotUseApiApp = ModuleRules.workerDoesNotUseApiApp(ROOT);
}
