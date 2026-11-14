package com.flightaggregator.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Evaluates the hexagonal rules against the production classes of every module. */
class HexagonalArchitectureTest {

  private static final String ROOT = "com.flightaggregator";

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages(ROOT);

  // The modules hold only package-info until plan 2, so no class matches yet. Remove
  // allowEmptyShould(true) from every test here when plan 2 adds the first classes.
  @Test
  void domainDependsOnlyOnTheJdk() {
    HexagonalRules.domainDependsOnlyOnTheJdk(ROOT).allowEmptyShould(true).check(PRODUCTION);
  }

  @Test
  void applicationDependsOnlyOnTheDomainAndSpring() {
    HexagonalRules.applicationDependsOnlyOnTheDomainAndSpring(ROOT)
        .allowEmptyShould(true)
        .check(PRODUCTION);
  }

  @Test
  void adaptersUseOnlyPorts() {
    HexagonalRules.adaptersUseOnlyPorts(ROOT).allowEmptyShould(true).check(PRODUCTION);
  }

  @Test
  void adaptersAreIndependent() {
    HexagonalRules.adaptersAreIndependent(ROOT).allowEmptyShould(true).check(PRODUCTION);
  }

  @Test
  void nothingDependsOnBootstrap() {
    HexagonalRules.nothingDependsOnBootstrap(ROOT).allowEmptyShould(true).check(PRODUCTION);
  }
}
