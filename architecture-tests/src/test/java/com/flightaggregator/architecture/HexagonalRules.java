package com.flightaggregator.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/** The dependency rules of the hexagonal architecture, parameterised by the root package. */
final class HexagonalRules {

  private HexagonalRules() {}

  static ArchRule domainDependsOnlyOnTheJdk(String rootPackage) {
    return classes()
        .that()
        .resideInAPackage(rootPackage + ".domain..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("java..", rootPackage + ".domain..");
  }

  // Spring is allowed in the application layer, never in the domain (design spec, section 6).
  static ArchRule applicationDependsOnlyOnTheDomainAndSpring(String rootPackage) {
    return classes()
        .that()
        .resideInAPackage(rootPackage + ".application..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "java..",
            "org.springframework..",
            rootPackage + ".domain..",
            rootPackage + ".application..");
  }

  static ArchRule adaptersUseOnlyPorts(String rootPackage) {
    return noClasses()
        .that()
        .resideInAPackage(rootPackage + ".adapter..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(rootPackage + ".application.service..");
  }

  static ArchRule adaptersAreIndependent(String rootPackage) {
    return slices().matching(rootPackage + ".adapter.(*)..").should().notDependOnEachOther();
  }

  static ArchRule nothingDependsOnBootstrap(String rootPackage) {
    return noClasses()
        .that()
        .resideOutsideOfPackage(rootPackage + ".bootstrap..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(rootPackage + ".bootstrap..");
  }

  static List<ArchRule> forRoot(String rootPackage) {
    return List.of(
        domainDependsOnlyOnTheJdk(rootPackage),
        applicationDependsOnlyOnTheDomainAndSpring(rootPackage),
        adaptersUseOnlyPorts(rootPackage),
        adaptersAreIndependent(rootPackage),
        nothingDependsOnBootstrap(rootPackage));
  }
}
