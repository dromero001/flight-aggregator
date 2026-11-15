package com.flightaggregator.adapter.provider.beta;

import com.flightaggregator.adapter.provider.ParsedPage;
import com.flightaggregator.adapter.provider.RecordFields;
import com.flightaggregator.adapter.provider.RecordRejected;
import com.flightaggregator.application.port.out.DiscardReason;
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

  /**
   * A segment's values by field name, in document order, and whether it is of Beta's shape: no
   * field repeated, and no known field holding elements.
   */
  private static final class SegmentFields {

    private final Map<String, String> values = new LinkedHashMap<>();
    private boolean wellShaped = true;

    /** Keeps the first value of a field; a second one, or a field's value that is null, is not. */
    void add(String name, String value) {
      if (value != null && values.putIfAbsent(name, value) != null) {
        wellShaped = false;
      }
    }

    /** The element's text. An element that holds elements has none: it is ill shaped. */
    String leafText(XMLStreamReader xml) throws XMLStreamException {
      StringBuilder text = new StringBuilder();
      if (!consume(xml, text)) {
        wellShaped = false;
        return null;
      }
      return text.toString();
    }

    String excerpt() {
      return values.entrySet().stream()
          .map(field -> field.getKey() + "=" + field.getValue())
          .collect(Collectors.joining(" "));
    }
  }

  /** Reads the whole segment, so a segment that is discarded leaves its neighbours readable. */
  private static SegmentFields readSegment(XMLStreamReader xml) throws XMLStreamException {
    SegmentFields segment = new SegmentFields();
    while (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
      switch (xml.getLocalName()) {
        case "Carrier" -> {
          segment.add("carrier", xml.getAttributeValue(null, "code"));
          segment.leafText(xml);
        }
        case "FlightNumber" -> segment.add("flightNumber", segment.leafText(xml));
        case "Departure" -> {
          segment.add("origin", xml.getAttributeValue(null, "airport"));
          segment.add("departureTime", segment.leafText(xml));
        }
        case "Arrival" -> {
          segment.add("destination", xml.getAttributeValue(null, "airport"));
          segment.add("arrivalTime", segment.leafText(xml));
        }
        case "PriceCents" -> segment.add("price", segment.leafText(xml));
        default -> consume(xml, new StringBuilder());
      }
    }
    return segment;
  }

  /**
   * Leaves the reader on the end of the element it is on, collecting the text directly inside it.
   * Returns false when the element holds elements.
   */
  private static boolean consume(XMLStreamReader xml, StringBuilder text)
      throws XMLStreamException {
    boolean leaf = true;
    for (int depth = 1; depth > 0; ) {
      int event = xml.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        depth++;
        leaf = false;
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        depth--;
      } else if (depth == 1 && event != XMLStreamConstants.COMMENT) {
        text.append(xml.getText());
      }
    }
    return leaf;
  }

  private static Leg leg(SegmentFields segment, Instant quotedAt) {
    if (!segment.wellShaped) {
      throw new RecordRejected(DiscardReason.SHAPE);
    }
    Map<String, String> fields = segment.values;
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
