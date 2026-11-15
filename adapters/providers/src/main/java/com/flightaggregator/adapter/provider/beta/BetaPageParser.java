package com.flightaggregator.adapter.provider.beta;

import com.flightaggregator.adapter.provider.ParsedPage;
import com.flightaggregator.adapter.provider.RecordFields;
import com.flightaggregator.adapter.provider.RecordRejected;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderText;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

  private static final Set<String> FIELD_ELEMENTS =
      Set.of("Carrier", "FlightNumber", "Departure", "Arrival", "PriceCents");

  private BetaPageParser() {}

  /** No DTD and no external entities: a provider's page is data, and reads nothing of ours. */
  private static XMLInputFactory factory() {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    return factory;
  }

  static ParsedPage parse(String body, Instant quotedAt, int requestedPage) {
    try {
      XMLStreamReader xml = FACTORY.createXMLStreamReader(new StringReader(body));
      try {
        return page(xml, quotedAt, requestedPage);
      } finally {
        xml.close();
      }
    } catch (XMLStreamException e) {
      throw malformed(e);
    }
  }

  private static ParsedPage page(XMLStreamReader xml, Instant quotedAt, int requestedPage)
      throws XMLStreamException {
    expectStart(xml, "SegmentsResponse");
    int page = positiveInteger(xml, "page");
    if (page != requestedPage) {
      throw new XMLStreamException("Asked for page " + requestedPage + ", got page " + page);
    }
    int totalPages = positiveInteger(xml, "totalPages");
    expectStart(xml, "Segments");
    List<Leg> legs = new ArrayList<>();
    List<DiscardedRecord> discarded = new ArrayList<>();
    while (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
      if (!xml.getLocalName().equals("Segment")) {
        discarded.add(new DiscardedRecord(DiscardReason.SHAPE, "element=" + xml.getLocalName()));
        readElement(xml);
        continue;
      }
      SegmentFields segment = readSegment(xml);
      try {
        legs.add(leg(segment, quotedAt));
      } catch (RecordRejected rejected) {
        discarded.add(new DiscardedRecord(rejected.reason(), segment.excerpt()));
      }
    }
    expectEnd(xml, "SegmentsResponse");
    while (xml.hasNext()) {
      xml.next(); // the parser refuses whatever well-formedness the rest of the body lacks
    }
    Optional<String> next =
        requestedPage < totalPages
            ? Optional.of(Integer.toString(requestedPage + 1))
            : Optional.empty();
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
      throw new XMLStreamException(
          "Expected <" + name + ">, not the element " + ProviderText.quoted(xml.getLocalName()));
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

  /**
   * A segment's values by field name, in document order, and whether it is of Beta's shape: no
   * stray text, no known element twice, no known element holding elements. A field of an ill-shaped
   * element is not kept.
   */
  private record SegmentFields(Map<String, String> values, boolean wellShaped) {

    String excerpt() {
      return values.entrySet().stream()
          .map(field -> field.getKey() + "=" + field.getValue())
          .collect(Collectors.joining(" "));
    }
  }

  /** The text of an element, and whether it holds only text. */
  private record Content(String text, boolean leaf) {}

  /**
   * Reads the whole segment, so a segment that is discarded leaves its neighbours readable. This
   * loop alone decides the segment's shape.
   */
  private static SegmentFields readSegment(XMLStreamReader xml) throws XMLStreamException {
    Map<String, String> values = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    boolean wellShaped = true;
    while (true) {
      switch (xml.next()) {
        case XMLStreamConstants.END_ELEMENT -> {
          return new SegmentFields(values, wellShaped);
        }
        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
          wellShaped &= xml.isWhiteSpace();
        }
        case XMLStreamConstants.START_ELEMENT -> {
          String name = xml.getLocalName();
          String code = xml.getAttributeValue(null, "code");
          String airport = xml.getAttributeValue(null, "airport");
          Content content = readElement(xml);
          if (!FIELD_ELEMENTS.contains(name)) {
            continue;
          }
          if (!seen.add(name) || !content.leaf()) {
            wellShaped = false;
          } else {
            switch (name) {
              case "Carrier" -> put(values, "carrier", code);
              case "FlightNumber" -> put(values, "flightNumber", content.text());
              case "Departure" -> {
                put(values, "origin", airport);
                put(values, "departureTime", content.text());
              }
              case "Arrival" -> {
                put(values, "destination", airport);
                put(values, "arrivalTime", content.text());
              }
              case "PriceCents" -> put(values, "price", content.text());
              default -> throw new IllegalStateException("No mapping for the field " + name);
            }
          }
        }
        default -> {} // comments, processing instructions and the like carry nothing
      }
    }
  }

  /** A field that the element lacks (null) is absent, which {@link RecordFields} rejects. */
  private static void put(Map<String, String> values, String name, String value) {
    if (value != null) {
      values.put(name, value);
    }
  }

  /**
   * Leaves the reader on the end of the element it is on, collecting the text directly inside it.
   */
  private static Content readElement(XMLStreamReader xml) throws XMLStreamException {
    StringBuilder text = new StringBuilder();
    boolean leaf = true;
    for (int depth = 1; depth > 0; ) {
      switch (xml.next()) {
        case XMLStreamConstants.START_ELEMENT -> {
          depth++;
          leaf = false;
        }
        case XMLStreamConstants.END_ELEMENT -> depth--;
        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
          if (depth == 1) {
            text.append(xml.getText());
          }
        }
        default -> {}
      }
    }
    return new Content(text.toString(), leaf);
  }

  private static Leg leg(SegmentFields segment, Instant quotedAt) {
    if (!segment.wellShaped()) {
      throw new RecordRejected(DiscardReason.SHAPE);
    }
    Map<String, String> fields = segment.values();
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
