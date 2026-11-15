package com.flightaggregator.architecture;

import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.stream.Stream;
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

  static Stream<Arguments> rules() {
    return HexagonalRules.forRoot(ROOT).stream()
        .map(rule -> argumentSet(rule.getDescription(), rule));
  }

  @ParameterizedTest
  @MethodSource("rules")
  void rulesOverPopulatedLayers(ArchRule rule) {
    rule.check(PRODUCTION);
  }
}
