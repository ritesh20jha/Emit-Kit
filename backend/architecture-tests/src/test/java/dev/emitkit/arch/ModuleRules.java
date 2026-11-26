package dev.emitkit.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Module dependency rules (ov §2). Each factory takes the root package so the same rules can be
 * exercised against fixtures.
 */
public final class ModuleRules {

  private ModuleRules() {}

  public static ArchRule commonIsIndependent(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + ".common..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            root + ".domain..",
            root + ".vcs..",
            root + ".spec..",
            root + ".gen..",
            root + ".ai..",
            root + ".api..",
            root + ".worker..")
        .because("common is the leaf module and depends on no other module");
  }

  public static ArchRule domainOnlyUsesCommon(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + ".domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            root + ".vcs..",
            root + ".spec..",
            root + ".gen..",
            root + ".ai..",
            root + ".api..",
            root + ".worker..")
        .because("domain may only depend on common");
  }

  public static ArchRule specHasNoSpring(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + ".spec..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework..")
        .because("the spec engine is framework-free");
  }

  public static ArchRule generatorsUseOnlySpecAndCommon(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + ".gen..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            root + ".domain..",
            root + ".vcs..",
            root + ".ai..",
            root + ".api..",
            root + ".worker..")
        .because("generators may only depend on spec-engine and common");
  }

  public static ArchRule generatorsDoNoIo(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + ".gen..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("java.net..")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.nio.file.Files")
        .because("generators are pure and perform no network or file I/O");
  }

  public static ArchRule vcsOnlyUsesCommon(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + ".vcs..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            root + ".domain..",
            root + ".spec..",
            root + ".gen..",
            root + ".ai..",
            root + ".api..",
            root + ".worker..")
        .because("vcs may only depend on common");
  }

  public static ArchRule aiDoesNotUseApps(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + ".ai..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(root + ".api..", root + ".worker..")
        .because("ai is a library and must not depend on application modules");
  }

  public static ArchRule nothingDependsOnApiApp(String root) {
    return noClasses()
        .that()
        .resideOutsideOfPackage(root + ".api..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(root + ".api..")
        .because("api-app is a leaf application; nothing may depend on it");
  }

  public static ArchRule workerDoesNotUseApiApp(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + ".worker..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(root + ".api..")
        .because("worker-app must not depend on api-app");
  }
}
