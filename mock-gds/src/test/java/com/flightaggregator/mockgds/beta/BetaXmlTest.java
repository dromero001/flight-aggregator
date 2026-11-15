package com.flightaggregator.mockgds.beta;

import static com.flightaggregator.mockgds.support.Fixtures.ib3170;
import static com.flightaggregator.mockgds.support.Fixtures.pageWithEveryDefect;
import static com.flightaggregator.mockgds.support.Fixtures.priced;
import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.serving.InventoryPage;
import com.flightaggregator.mockgds.support.Xml;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class BetaXmlTest {

  @Test
  void writesAValidSegment() {
    var page = new InventoryPage(List.of(priced(ib3170(Provider.BETA))), 0, 1);

    var xml = BetaXml.write(page, 1, 9);

    assertThat(xml)
        .isEqualTo(
            "<SegmentsResponse page=\"1\" totalPages=\"9\"><Segments><Segment>"
                + "<Carrier code=\"IB\"/><FlightNumber>3170</FlightNumber>"
                + "<Departure airport=\"MAD\">2026-11-20T08:15:00+01:00</Departure>"
                + "<Arrival airport=\"LHR\">2026-11-20T09:30:00+00:00</Arrival>"
                + "<PriceCents>12990</PriceCents></Segment></Segments></SegmentsResponse>");
  }

  @Test
  void writesEachDefectInItsOwnSegment() throws Exception {
    var xml = BetaXml.write(pageWithEveryDefect(Provider.BETA), 1, 1);

    var segments = Xml.parse(xml).getElementsByTagName("Segment");
    assertThat(segments.getLength()).isEqualTo(5);
    var valid = (Element) segments.item(0);
    assertThat(valid.getElementsByTagName("FlightNumber").getLength()).isEqualTo(1);
    var missing = (Element) segments.item(1);
    assertThat(missing.getElementsByTagName("FlightNumber").getLength()).isZero();
    var badDate = (Element) segments.item(2);
    assertThat(text(badDate, "Departure")).isEqualTo("2026-02-30T25:61:00+01:00");
    var negative = (Element) segments.item(3);
    assertThat(text(negative, "PriceCents")).isEqualTo("-12990");
    var unknown = (Element) segments.item(4);
    assertThat(((Element) unknown.getElementsByTagName("Arrival").item(0)).getAttribute("airport"))
        .isEqualTo("ZZZ");
  }

  private static String text(Element segment, String child) {
    return segment.getElementsByTagName(child).item(0).getTextContent();
  }

  @Test
  void writesNoDeclarationOrNamespace() {
    var xml = BetaXml.write(new InventoryPage(List.of(priced(ib3170(Provider.BETA))), 0, 1), 1, 1);

    assertThat(xml)
        .startsWith("<SegmentsResponse ")
        .doesNotContain("<?xml")
        .doesNotContain("xmlns");
  }

  @Test
  void writesAnEmptyPageAsAnEmptySegmentsElement() {
    var xml = BetaXml.write(new InventoryPage(List.of(), 5, 5), 3, 1);

    assertThat(xml)
        .isEqualTo("<SegmentsResponse page=\"3\" totalPages=\"1\"><Segments/></SegmentsResponse>");
  }
}
