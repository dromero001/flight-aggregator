package com.flightaggregator.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HexagonalRulesTest {

  private static final String FIXTURES = "com.flightaggregator.architecture.fixture.";

  private static JavaClasses classesIn(String fixture) {
    return new ClassFileImporter().importPackages(FIXTURES + fixture);
  }

  private static EvaluationResult evaluate(String fixture, Function<String, ArchRule> rule) {
    return rule.apply(FIXTURES + fixture).evaluate(classesIn(fixture));
  }

  private static String violations(EvaluationResult result) {
    return String.join("\n", result.getFailureReport().getDetails());
  }

  @Test
  void domainDependingOnAnAdapterIsAViolation() {
    EvaluationResult result = evaluate("domainleak", HexagonalRules::domainDependsOnlyOnTheJdk);

    assertThat(violations(result)).contains("domainleak.adapter.web.Controller");
  }

  @Test
  void domainUsingSpringIsAViolation() {
    EvaluationResult result = evaluate("domainspring", HexagonalRules::domainDependsOnlyOnTheJdk);

    assertThat(violations(result)).contains("org.springframework.stereotype.Component");
  }

  @Test
  void applicationDependingOnAnAdapterIsAViolation() {
    EvaluationResult result =
        evaluate("applicationleak", HexagonalRules::applicationDependsOnlyOnTheDomainAndSpring);

    assertThat(violations(result)).contains("applicationleak.adapter.web.Controller");
  }

  @Test
  void applicationUsingSpringIsNotAViolation() {
    EvaluationResult result =
        evaluate("applicationspring", HexagonalRules::applicationDependsOnlyOnTheDomainAndSpring);

    assertThat(result.hasViolation()).isFalse();
  }

  @Test
  void anAdapterDependingOnAServiceIsAViolation() {
    EvaluationResult result = evaluate("serviceaccess", HexagonalRules::adaptersUseOnlyPorts);

    assertThat(violations(result)).contains("serviceaccess.application.service.Searcher");
  }

  @Test
  void anAdapterDependingOnAnApplicationClassOutsideThePortsIsAViolation() {
    EvaluationResult result = evaluate("supportaccess", HexagonalRules::adaptersUseOnlyPorts);

    assertThat(violations(result)).contains("supportaccess.application.support.Coordinator");
  }

  @Test
  void anAdapterDependingOnAnotherAdapterIsAViolation() {
    EvaluationResult result = evaluate("adaptercoupling", HexagonalRules::adaptersAreIndependent);

    assertThat(violations(result)).contains("adaptercoupling.adapter.persistence.Repository");
  }

  @Test
  void dependingOnBootstrapIsAViolation() {
    EvaluationResult result = evaluate("bootstrapleak", HexagonalRules::nothingDependsOnBootstrap);

    assertThat(violations(result)).contains("bootstrapleak.bootstrap.Wiring");
  }

  @Test
  void aClassOutsideTheLayerPackagesIsAViolation() {
    EvaluationResult result = evaluate("layertypo", HexagonalRules::everyClassResidesInALayer);

    assertThat(violations(result)).contains("layertypo.adapters.web.Controller");
  }

  @Test
  void aDomainClassReadingTheClockIsAViolation() {
    EvaluationResult result =
        evaluate("clockread", HexagonalRules::domainNeverReadsTheClockOrRandomness);

    assertThat(violations(result)).contains("calls method <java.time.Instant.now()>");
  }

  @Test
  void aDomainClassDrawingARandomUuidIsAViolation() {
    EvaluationResult result =
        evaluate("randomread", HexagonalRules::domainNeverReadsTheClockOrRandomness);

    assertThat(violations(result)).contains("calls method <java.util.UUID.randomUUID()>");
  }

  @Test
  void aDomainClassDependingOnAClockIsAViolation() {
    EvaluationResult result =
        evaluate("clockfield", HexagonalRules::domainNeverReadsTheClockOrRandomness);

    assertThat(violations(result)).contains("has type <java.time.Clock>");
  }

  @Test
  void aDomainClassUsingARandomGeneratorIsAViolation() {
    EvaluationResult result =
        evaluate("randomtype", HexagonalRules::domainNeverReadsTheClockOrRandomness);

    assertThat(violations(result)).contains("has type <java.util.random.RandomGenerator>");
  }

  @Test
  void aDomainClassReadingTheSystemMillisIsAViolation() {
    EvaluationResult result =
        evaluate("millisread", HexagonalRules::domainNeverReadsTheClockOrRandomness);

    assertThat(violations(result)).contains("calls method <java.lang.System.currentTimeMillis()>");
  }

  static Stream<Arguments> rulesForTheCleanFixture() {
    return HexagonalRules.forRoot(FIXTURES + "clean").stream()
        .map(rule -> argumentSet(rule.getDescription(), rule));
  }

  @ParameterizedTest
  @MethodSource("rulesForTheCleanFixture")
  void theCleanFixtureSatisfiesTheRule(ArchRule rule) {
    assertThat(rule.evaluate(classesIn("clean")).hasViolation()).isFalse();
  }
}
