package com.flightaggregator.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class HexagonalRulesTest {

  private static final String FIXTURES = "com.flightaggregator.architecture.fixture.";

  private static EvaluationResult evaluate(String fixture, Function<String, ArchRule> rule) {
    String root = FIXTURES + fixture;
    return rule.apply(root).evaluate(new ClassFileImporter().importPackages(root));
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
  void aCleanDomainIsNotAViolation() {
    String root = FIXTURES + "clean";
    JavaClasses classes = new ClassFileImporter().importPackages(root);

    assertThatCode(() -> HexagonalRules.domainDependsOnlyOnTheJdk(root).check(classes))
        .doesNotThrowAnyException();
  }

  @Test
  void aCleanApplicationIsNotAViolation() {
    String root = FIXTURES + "clean";
    JavaClasses classes = new ClassFileImporter().importPackages(root);

    assertThatCode(
            () -> HexagonalRules.applicationDependsOnlyOnTheDomainAndSpring(root).check(classes))
        .doesNotThrowAnyException();
  }

  @Test
  void cleanAdaptersUseOnlyPorts() {
    String root = FIXTURES + "clean";
    JavaClasses classes = new ClassFileImporter().importPackages(root);

    assertThatCode(() -> HexagonalRules.adaptersUseOnlyPorts(root).check(classes))
        .doesNotThrowAnyException();
  }

  @Test
  void cleanAdaptersAreIndependent() {
    String root = FIXTURES + "clean";
    JavaClasses classes = new ClassFileImporter().importPackages(root);

    assertThatCode(() -> HexagonalRules.adaptersAreIndependent(root).check(classes))
        .doesNotThrowAnyException();
  }

  @Test
  void aCleanBootstrapIsNotAViolation() {
    String root = FIXTURES + "clean";
    JavaClasses classes = new ClassFileImporter().importPackages(root);

    assertThatCode(() -> HexagonalRules.nothingDependsOnBootstrap(root).check(classes))
        .doesNotThrowAnyException();
  }

  @Test
  void forRootReturnsTheFiveRulesInOrder() {
    String root = FIXTURES + "clean";

    assertThat(HexagonalRules.forRoot(root))
        .extracting(ArchRule::getDescription)
        .containsExactly(
            HexagonalRules.domainDependsOnlyOnTheJdk(root).getDescription(),
            HexagonalRules.applicationDependsOnlyOnTheDomainAndSpring(root).getDescription(),
            HexagonalRules.adaptersUseOnlyPorts(root).getDescription(),
            HexagonalRules.adaptersAreIndependent(root).getDescription(),
            HexagonalRules.nothingDependsOnBootstrap(root).getDescription());
  }
}
