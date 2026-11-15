package com.flightaggregator.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
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

  private static final List<ArchRule> ALL_RULES = HexagonalRules.forRoot(ROOT);

  // Rules 3 and 4 still match no class: plan 4 moves them out of this list with the first adapter
  // classes. They are picked by identity, so reordering forRoot changes nothing and a new rule is
  // strict by default.
  private static final List<ArchRule> LENIENT_RULES =
      List.of(
          HexagonalRules.adaptersUseOnlyPorts(ROOT), HexagonalRules.adaptersAreIndependent(ROOT));

  private static final List<ArchRule> STRICT_RULES =
      ALL_RULES.stream()
          .filter(rule -> !descriptions(LENIENT_RULES).contains(rule.getDescription()))
          .toList();

  private static List<String> descriptions(List<ArchRule> rules) {
    return rules.stream().map(ArchRule::getDescription).toList();
  }

  static Stream<Arguments> strictRules() {
    return named(STRICT_RULES);
  }

  static Stream<Arguments> lenientRules() {
    return named(LENIENT_RULES);
  }

  private static Stream<Arguments> named(List<ArchRule> rules) {
    return rules.stream().map(rule -> argumentSet(rule.getDescription(), rule));
  }

  @ParameterizedTest
  @MethodSource("strictRules")
  void rulesOverPopulatedLayers(ArchRule rule) {
    rule.check(PRODUCTION);
  }

  @ParameterizedTest
  @MethodSource("lenientRules")
  void rulesOverEmptyLayers(ArchRule rule) {
    rule.allowEmptyShould(true).check(PRODUCTION);
  }

  @Test
  void lenientRulesAreInForRootAndHideNoStrictRule() {
    List<String> checked = new ArrayList<>(descriptions(STRICT_RULES));
    checked.addAll(descriptions(LENIENT_RULES));

    assertThat(checked).containsExactlyInAnyOrderElementsOf(descriptions(ALL_RULES));
  }
}
