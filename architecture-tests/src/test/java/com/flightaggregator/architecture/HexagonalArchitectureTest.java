package com.flightaggregator.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Evaluates every hexagonal rule against the production classes of every module. */
class HexagonalArchitectureTest {

  private static final String ROOT = "com.flightaggregator";

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages(ROOT);

  private static final List<ArchRule> RULES = HexagonalRules.forRoot(ROOT);

  static Stream<Arguments> rulesOverPopulatedLayers() {
    return Stream.of(RULES.get(0), RULES.get(4), RULES.get(5))
        .map(HexagonalArchitectureTest::named);
  }

  // Rules 2, 3 and 4 still match no class: plan 4 moves them to rulesOverPopulatedLayers with the
  // first application and adapter classes.
  static Stream<Arguments> rulesOverEmptyLayers() {
    return Stream.of(RULES.get(1), RULES.get(2), RULES.get(3))
        .map(HexagonalArchitectureTest::named);
  }

  private static Arguments named(ArchRule rule) {
    return argumentSet(rule.getDescription(), rule);
  }

  @ParameterizedTest
  @MethodSource("rulesOverPopulatedLayers")
  void rulesOverPopulatedLayers(ArchRule rule) {
    rule.check(PRODUCTION);
  }

  @ParameterizedTest
  @MethodSource("rulesOverEmptyLayers")
  void rulesOverEmptyLayers(ArchRule rule) {
    rule.allowEmptyShould(true).check(PRODUCTION);
  }

  @Test
  void everyRuleIsChecked() {
    List<String> checked =
        Stream.concat(rulesOverPopulatedLayers(), rulesOverEmptyLayers())
            .map(arguments -> ((ArchRule) arguments.get()[0]).getDescription())
            .toList();

    assertThat(checked)
        .containsExactlyInAnyOrderElementsOf(RULES.stream().map(ArchRule::getDescription).toList());
  }
}
