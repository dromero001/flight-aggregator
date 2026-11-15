package com.flightaggregator.mockgds.beta;

import com.flightaggregator.mockgds.serving.InventoryPage;
import com.flightaggregator.mockgds.serving.PricedRecord;
import com.flightaggregator.mockgds.serving.RenderedLeg;
import java.io.StringWriter;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/** Writes a page of Beta's inventory as nested XML, with no declaration and no namespace. */
final class BetaXml {

  private BetaXml() {}

  static String write(InventoryPage page, int pageNumber, int totalPages) {
    var out = new StringWriter();
    try {
      XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
      xml.writeStartElement("SegmentsResponse");
      xml.writeAttribute("page", Integer.toString(pageNumber));
      xml.writeAttribute("totalPages", Integer.toString(totalPages));
      if (page.records().isEmpty()) {
        xml.writeEmptyElement("Segments");
      } else {
        xml.writeStartElement("Segments");
        for (PricedRecord priced : page.records()) {
          writeSegment(xml, RenderedLeg.of(priced));
        }
        xml.writeEndElement();
      }
      xml.writeEndElement();
      xml.close();
    } catch (XMLStreamException e) {
      throw new IllegalStateException("Cannot write Beta's XML", e);
    }
    return out.toString();
  }

  private static void writeSegment(XMLStreamWriter xml, RenderedLeg leg) throws XMLStreamException {
    xml.writeStartElement("Segment");
    xml.writeEmptyElement("Carrier");
    xml.writeAttribute("code", leg.carrier());
    if (leg.flightNumber() != null) {
      xml.writeStartElement("FlightNumber");
      xml.writeCharacters(leg.flightNumber().toString());
      xml.writeEndElement();
    }
    writeAirportTime(xml, "Departure", leg.origin(), leg.departureTime());
    writeAirportTime(xml, "Arrival", leg.destination(), leg.arrivalTime());
    xml.writeStartElement("PriceCents");
    xml.writeCharacters(Integer.toString(leg.price().movePointRight(2).intValueExact()));
    xml.writeEndElement();
    xml.writeEndElement();
  }

  private static void writeAirportTime(
      XMLStreamWriter xml, String element, String airport, String time) throws XMLStreamException {
    xml.writeStartElement(element);
    xml.writeAttribute("airport", airport);
    xml.writeCharacters(time);
    xml.writeEndElement();
  }
}
