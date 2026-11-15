package com.flightaggregator.mockgds.beta;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.dataset.Defect;
import com.flightaggregator.mockgds.dataset.MalformedLeg;
import com.flightaggregator.mockgds.dataset.ValidLeg;
import com.flightaggregator.mockgds.model.Leg;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.serving.InventoryPage;
import com.flightaggregator.mockgds.serving.PricedRecord;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

class BetaXmlTest {

  @Test
  void writesAValidSegment() {
    var page = new InventoryPage(List.of(priced(validLeg())), 0, 1);

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
    var leg = validLeg();
    var records = new ArrayList<PricedRecord>();
    records.add(priced(leg));
    for (var defect : Defect.values()) {
      records.add(new PricedRecord(new MalformedLeg(leg, defect), leg.priceEur()));
    }

    var xml = BetaXml.write(new InventoryPage(records, 0, 5), 1, 1);

    var segments = parse(xml).getElementsByTagName("Segment");
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

  private static Element parse(String xml) throws Exception {
    return DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(new InputSource(new StringReader(xml)))
        .getDocumentElement();
  }

  private static String text(Element segment, String child) {
    return segment.getElementsByTagName(child).item(0).getTextContent();
  }

  private static Leg validLeg() {
    return new Leg(
        "IB",
        3170,
        "MAD",
        "LHR",
        OffsetDateTime.parse("2026-11-20T08:15:00+01:00"),
        OffsetDateTime.parse("2026-11-20T09:30:00Z"),
        new BigDecimal("129.90"),
        Provider.BETA);
  }

  private static PricedRecord priced(Leg leg) {
    return new PricedRecord(new ValidLeg(leg), leg.priceEur());
  }

  @Test
  void writesNoDeclarationOrNamespace() {
    var xml = BetaXml.write(new InventoryPage(List.of(priced(validLeg())), 0, 1), 1, 1);

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
