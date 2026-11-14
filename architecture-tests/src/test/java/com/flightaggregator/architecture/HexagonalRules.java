package com.flightaggregator.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
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

  // Adapters reach the application only through its ports (design spec, section 6 "Ports").
  static ArchRule adaptersUseOnlyPorts(String rootPackage) {
    return noClasses()
        .that()
        .resideInAPackage(rootPackage + ".adapter..")
        .should()
        .dependOnClassesThat(
            resideInAPackage(rootPackage + ".application..")
                .and(not(resideInAPackage(rootPackage + ".application.port.."))));
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

  // Every other rule selects classes by layer package, so a class elsewhere (adapters.web instead
  // of adapter.web, say) would escape them all. mock-gds and the architecture tests are not layers.
  static ArchRule everyClassResidesInALayer(String rootPackage) {
    return classes()
        .that()
        .resideInAPackage(rootPackage + "..")
        .and()
        .resideOutsideOfPackages(rootPackage + ".mockgds..", rootPackage + ".architecture..")
        .should()
        .resideInAnyPackage(
            rootPackage + ".domain..",
            rootPackage + ".application.port.in..",
            rootPackage + ".application.port.out..",
            rootPackage + ".application.service..",
            rootPackage + ".adapter.provider..",
            rootPackage + ".adapter.persistence..",
            rootPackage + ".adapter.web..",
            rootPackage + ".bootstrap..");
  }

  static List<ArchRule> forRoot(String rootPackage) {
    return List.of(
        domainDependsOnlyOnTheJdk(rootPackage),
        applicationDependsOnlyOnTheDomainAndSpring(rootPackage),
        adaptersUseOnlyPorts(rootPackage),
        adaptersAreIndependent(rootPackage),
        nothingDependsOnBootstrap(rootPackage),
        everyClassResidesInALayer(rootPackage));
  }
}
