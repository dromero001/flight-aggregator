package com.flightaggregator.mockgds.gamma;

import static com.flightaggregator.mockgds.support.Fixtures.ib3170;
import static com.flightaggregator.mockgds.support.Fixtures.pageWithEveryDefect;
import static com.flightaggregator.mockgds.support.Fixtures.priced;
import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.dataset.DatasetGenerator;
import com.flightaggregator.mockgds.dataset.GeneratorConfig;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.reference.ReferenceData;
import com.flightaggregator.mockgds.serving.InventoryPage;
import com.flightaggregator.mockgds.serving.ProviderInventory;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class GammaCsvTest {

  @Test
  void writesTheHeaderAndAValidRow() {
    var page = new InventoryPage(List.of(priced(ib3170(Provider.GAMMA))), 0, 1);

    var csv = GammaCsv.write(page);

    assertThat(csv)
        .isEqualTo(
            "airline,flight_number,from,to,departs_at,arrives_at,price_eur\r\n"
                + "IB,3170,MAD,LHR,2026-11-20T08:15:00+01:00,2026-11-20T09:30:00+00:00,129.90\r\n");
  }

  @Test
  void writesEachDefectInItsOwnRow() {
    var csv = GammaCsv.write(pageWithEveryDefect(Provider.GAMMA));

    var lines = csv.split("\r\n", -1);
    assertThat(lines).hasSize(7);
    var rows = Arrays.stream(lines, 1, 6).map(line -> line.split(",", -1)).toList();
    assertThat(rows).allSatisfy(cells -> assertThat(cells).hasSize(7));
    assertThat(rows.get(0)[1]).isEqualTo("3170");
    assertThat(rows.get(1)[1]).isEmpty();
    assertThat(rows.get(2)[4]).isEqualTo("2026-02-30T25:61:00+01:00");
    assertThat(rows.get(3)[6]).isEqualTo("-129.90");
    assertThat(rows.get(4)[3]).isEqualTo("ZZZ");
  }

  @Test
  void writesOnlyTheHeaderAfterTheEnd() {
    var csv = GammaCsv.write(new InventoryPage(List.of(), 5, 5));

    assertThat(csv).isEqualTo("airline,flight_number,from,to,departs_at,arrives_at,price_eur\r\n");
  }

  /** Pins why the cells are written unquoted: no cell of the default dataset needs quoting. */
  @Test
  void noCellOfTheDefaultDatasetNeedsQuoting() {
    var generator = new DatasetGenerator(GeneratorConfig.defaults(), ReferenceData.standard());
    var clock = Clock.systemUTC();
    var inventory = new ProviderInventory(generator.generate(clock), generator.prices(), clock);

    var csv = GammaCsv.write(inventory.page(Provider.GAMMA, 0, Integer.MAX_VALUE));

    var lines = csv.split("\r\n");
    assertThat(lines.length).isGreaterThan(1000);
    for (var line : lines) {
      assertThat(line.split(",", -1)).hasSize(7).noneMatch(cell -> cell.contains("\""));
      assertThat(line).doesNotContain("\n").doesNotContain("\r");
    }
  }
}
