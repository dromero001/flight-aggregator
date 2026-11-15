package com.flightaggregator.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Money;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SearchKeyTest {

  @Test
  void namesTheGenerationTheSortEachFilterAndTheLimit() {
    SearchCriteria criteria =
        new SearchCriteria(
            Optional.of(new Airport("MAD")),
            Optional.of(new Airport("LHR")),
            Optional.of(Money.euros("150")),
            Optional.of(new Carrier("IB")),
            SortOrder.DURATION);

    assertThat(new SearchKey(7, criteria, 20).value())
        .isEqualTo("search:7:DURATION:MAD:LHR:IB:150.00:20");
  }

  @Test
  void writesEachAbsentFilterAsADash() {
    SearchCriteria criteria =
        new SearchCriteria(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            SortOrder.PRICE);

    assertThat(new SearchKey(0, criteria, 100).value()).isEqualTo("search:0:PRICE:-:-:-:-:100");
  }
}
