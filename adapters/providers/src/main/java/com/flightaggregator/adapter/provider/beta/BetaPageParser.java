package com.flightaggregator.adapter.provider.beta;

import com.flightaggregator.adapter.provider.ParsedPage;
import com.flightaggregator.adapter.provider.RecordFields;
import com.flightaggregator.adapter.provider.RecordRejected;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Parses one page of Beta's nested XML: {@code SegmentsResponse} with its {@code page} and {@code
 * totalPages}, around a list of {@code Segment}s. A segment that is not valid is discarded alone; a
 * page that is not well formed, or not of this shape, fails.
 */
final class BetaPageParser {

  private static final XMLInputFactory FACTORY = factory();

  /** A page number or count: positive, and far from {@code int}'s limit. */
  private static final Pattern POSITIVE_INTEGER = Pattern.compile("^[1-9][0-9]{0,8}$");

  private BetaPageParser() {}

  /** No DTD and no external entities: a provider's page is data, and reads nothing of ours. */
  private static XMLInputFactory factory() {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    return factory;
  }

  static ParsedPage parse(String body, Instant quotedAt) {
    try {
      XMLStreamReader xml = FACTORY.createXMLStreamReader(new StringReader(body));
      try {
        return page(xml, quotedAt);
      } finally {
        xml.close();
      }
    } catch (XMLStreamException e) {
      throw malformed(e);
    }
  }

  private static ParsedPage page(XMLStreamReader xml, Instant quotedAt) throws XMLStreamException {
    expectStart(xml, "SegmentsResponse");
    int page = positiveInteger(xml, "page");
    int totalPages = positiveInteger(xml, "totalPages");
    expectStart(xml, "Segments");
    List<Leg> legs = new ArrayList<>();
    List<DiscardedRecord> discarded = new ArrayList<>();
    while (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
      expectName(xml, "Segment");
      Map<String, String> fields = readSegment(xml);
      try {
        legs.add(leg(fields, quotedAt));
      } catch (RecordRejected rejected) {
        discarded.add(new DiscardedRecord(rejected.reason(), excerpt(fields)));
      }
    }
    expectEnd(xml, "SegmentsResponse");
    while (xml.hasNext()) {
      xml.next(); // the parser refuses whatever well-formedness the rest of the body lacks
    }
    Optional<String> next =
        page < totalPages ? Optional.of(Integer.toString(page + 1)) : Optional.empty();
    return new ParsedPage(legs, discarded, next, Optional.of(totalPages));
  }

  private static void expectStart(XMLStreamReader xml, String name) throws XMLStreamException {
    if (xml.nextTag() != XMLStreamConstants.START_ELEMENT) {
      throw new XMLStreamException("Expected <" + name + ">");
    }
    expectName(xml, name);
  }

  private static void expectEnd(XMLStreamReader xml, String name) throws XMLStreamException {
    if (xml.nextTag() != XMLStreamConstants.END_ELEMENT || !xml.getLocalName().equals(name)) {
      throw new XMLStreamException("Expected </" + name + ">");
    }
  }

  private static void expectName(XMLStreamReader xml, String name) throws XMLStreamException {
    if (!xml.getLocalName().equals(name)) {
      throw new XMLStreamException("Expected <" + name + ">, not <" + xml.getLocalName() + ">");
    }
  }

  private static int positiveInteger(XMLStreamReader xml, String attribute)
      throws XMLStreamException {
    String text = xml.getAttributeValue(null, attribute);
    if (text == null || !POSITIVE_INTEGER.matcher(text).matches()) {
      throw new XMLStreamException("The attribute " + attribute + " is not a positive integer");
    }
    return Integer.parseInt(text);
  }

  private static ProviderUnavailableException malformed(Throwable cause) {
    return new ProviderUnavailableException(Provider.BETA, FailureReason.MALFORMED_PAGE, cause);
  }

  /** The segment's values by field name, in document order; a field that is absent is absent. */
  private static Map<String, String> readSegment(XMLStreamReader xml) throws XMLStreamException {
    Map<String, String> fields = new LinkedHashMap<>();
    while (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
      switch (xml.getLocalName()) {
        case "Carrier" -> {
          fields.put("carrier", xml.getAttributeValue(null, "code"));
          xml.getElementText();
        }
        case "FlightNumber" -> fields.put("flightNumber", xml.getElementText());
        case "Departure" -> {
          fields.put("origin", xml.getAttributeValue(null, "airport"));
          fields.put("departureTime", xml.getElementText());
        }
        case "Arrival" -> {
          fields.put("destination", xml.getAttributeValue(null, "airport"));
          fields.put("arrivalTime", xml.getElementText());
        }
        case "PriceCents" -> fields.put("price", xml.getElementText());
        default -> skip(xml);
      }
    }
    return fields;
  }

  /** Leaves the reader on the end of the element it is on, whatever the element holds. */
  private static void skip(XMLStreamReader xml) throws XMLStreamException {
    for (int depth = 1; depth > 0; ) {
      int event = xml.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        depth++;
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        depth--;
      }
    }
  }

  private static String excerpt(Map<String, String> fields) {
    return fields.entrySet().stream()
        .map(field -> field.getKey() + "=" + field.getValue())
        .collect(Collectors.joining(" "));
  }

  private static Leg leg(Map<String, String> fields, Instant quotedAt) {
    return RecordFields.leg(
        RecordFields.carrier(fields.get("carrier")),
        RecordFields.flightNumber(fields.get("flightNumber")),
        RecordFields.airport(fields.get("origin")),
        RecordFields.airport(fields.get("destination")),
        RecordFields.time(fields.get("departureTime")),
        RecordFields.time(fields.get("arrivalTime")),
        RecordFields.centsPrice(fields.get("price")),
        Provider.BETA,
        quotedAt);
  }
}
