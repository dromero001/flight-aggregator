package com.flightaggregator.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

class HexagonalRulesTest {

  private static final String FIXTURES = "com.flightaggregator.architecture.fixture.";

  @Test
  void domainDependingOnAnAdapterIsAViolation() {
    String root = FIXTURES + "domainleak";
    JavaClasses classes = new ClassFileImporter().importPackages(root);

    EvaluationResult result = HexagonalRules.domainDependsOnlyOnTheJdk(root).evaluate(classes);

    assertThat(result.hasViolation()).isTrue();
  }

  @Test
  void applicationDependingOnAnAdapterIsAViolation() {
    String root = FIXTURES + "applicationleak";
    JavaClasses classes = new ClassFileImporter().importPackages(root);

    EvaluationResult result =
        HexagonalRules.applicationDependsOnlyOnTheDomainAndSpring(root).evaluate(classes);

    assertThat(result.hasViolation()).isTrue();
  }

  @Test
  void applicationUsingSpringIsNotAViolation() {
    String root = FIXTURES + "applicationspring";
    JavaClasses classes = new ClassFileImporter().importPackages(root);

    EvaluationResult result =
        HexagonalRules.applicationDependsOnlyOnTheDomainAndSpring(root).evaluate(classes);

    assertThat(result.hasViolation()).isFalse();
  }

  @Test
  void anAdapterDependingOnAServiceIsAViolation() {
    String root = FIXTURES + "serviceaccess";
    JavaClasses classes = new ClassFileImporter().importPackages(root);

    EvaluationResult result = HexagonalRules.adaptersUseOnlyPorts(root).evaluate(classes);

    assertThat(result.hasViolation()).isTrue();
  }

  @Test
  void anAdapterDependingOnAnotherAdapterIsAViolation() {
    String root = FIXTURES + "adaptercoupling";
    JavaClasses classes = new ClassFileImporter().importPackages(root);

    EvaluationResult result = HexagonalRules.adaptersAreIndependent(root).evaluate(classes);

    assertThat(result.hasViolation()).isTrue();
  }

  @Test
  void dependingOnBootstrapIsAViolation() {
    String root = FIXTURES + "bootstrapleak";
    JavaClasses classes = new ClassFileImporter().importPackages(root);

    EvaluationResult result = HexagonalRules.nothingDependsOnBootstrap(root).evaluate(classes);

    assertThat(result.hasViolation()).isTrue();
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
