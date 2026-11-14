package com.flightaggregator.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.conditions.ArchConditions.dependOnClassesThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Clock;
import java.time.InstantSource;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.UUID;

/** The dependency rules of the hexagonal architecture, parameterised by the root package. */
final class HexagonalRules {

  private HexagonalRules() {}

  private static final DescribedPredicate<JavaClass> CLOCK_AND_RANDOM_TYPES =
      belongToAnyOf(Clock.class, InstantSource.class, SplittableRandom.class)
          .or(assignableTo(Random.class))
          .or(resideInAPackage("java.util.random.."));

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

  // Same inventory, same itineraries, whatever day or hour the tests run (plan 2, global
  // constraints). Rule 1 allows every java.* type, so only this rule keeps the clock out.
  static ArchRule domainNeverReadsTheClockOrRandomness(String rootPackage) {
    return noClasses()
        .that()
        .resideInAPackage(rootPackage + ".domain..")
        .should(
            callMethodWhere(
                    callToNowOfAJavaTimeClass()
                        .or(callTo(System.class, "currentTimeMillis"))
                        .or(callTo(System.class, "nanoTime"))
                        .or(callTo(Math.class, "random"))
                        .or(callTo(StrictMath.class, "random"))
                        .or(callTo(UUID.class, "randomUUID")))
                .or(dependOnClassesThat(CLOCK_AND_RANDOM_TYPES))
                .as("read the clock or randomness"));
  }

  private static DescribedPredicate<JavaCall<?>> callToNowOfAJavaTimeClass() {
    return target(owner(resideInAPackage("java.time.."))).and(target(name("now")));
  }

  private static DescribedPredicate<JavaCall<?>> callTo(Class<?> owner, String method) {
    return target(owner(belongToAnyOf(owner))).and(target(name(method)));
  }

  static List<ArchRule> forRoot(String rootPackage) {
    return List.of(
        domainDependsOnlyOnTheJdk(rootPackage),
        applicationDependsOnlyOnTheDomainAndSpring(rootPackage),
        adaptersUseOnlyPorts(rootPackage),
        adaptersAreIndependent(rootPackage),
        nothingDependsOnBootstrap(rootPackage),
        everyClassResidesInALayer(rootPackage),
        domainNeverReadsTheClockOrRandomness(rootPackage));
  }
}
