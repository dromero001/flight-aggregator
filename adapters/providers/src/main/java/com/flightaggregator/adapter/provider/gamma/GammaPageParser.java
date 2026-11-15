package com.flightaggregator.adapter.provider.gamma;

import com.flightaggregator.adapter.provider.ParsedPage;
import com.flightaggregator.adapter.provider.RecordFields;
import com.flightaggregator.adapter.provider.RecordRejected;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses one page of Gamma's CSV: a header row, then one leg per line, with the columns read by
 * name. A page with {@code limit} lines, valid or not, is followed by the page at {@code offset +
 * limit}, computed from the requested values; a shorter page ends the download. A line that is not
 * valid is discarded alone; a page without a required column fails.
 */
final class GammaPageParser {

  private static final String QUOTE = "\"";

  private GammaPageParser() {}

  static ParsedPage parse(String body, Instant quotedAt, int offset, int limit) {
    String[] lines = body.split("\r\n");
    Columns columns = Columns.of(List.of(lines[0].split(",", -1)));
    List<Leg> legs = new ArrayList<>();
    List<DiscardedRecord> discarded = new ArrayList<>();
    for (int i = 1; i < lines.length; i++) {
      try {
        legs.add(leg(columns, lines[i], quotedAt));
      } catch (RecordRejected rejected) {
        discarded.add(new DiscardedRecord(rejected.reason(), lines[i]));
      }
    }
    Optional<String> next =
        lines.length - 1 == limit
            ? Optional.of(Integer.toString(offset + limit))
            : Optional.empty();
    return new ParsedPage(legs, discarded, next);
  }

  private static Leg leg(Columns columns, String line, Instant quotedAt) {
    List<String> cells = List.of(line.split(",", -1));
    if (cells.size() != columns.count() || line.contains(QUOTE)) {
      throw new RecordRejected(DiscardReason.SHAPE);
    }
    return RecordFields.leg(
        RecordFields.carrier(cells.get(columns.airline())),
        RecordFields.flightNumber(cells.get(columns.flightNumber())),
        RecordFields.airport(cells.get(columns.from())),
        RecordFields.airport(cells.get(columns.to())),
        RecordFields.time(cells.get(columns.departsAt())),
        RecordFields.time(cells.get(columns.arrivesAt())),
        RecordFields.decimalPrice(cells.get(columns.priceEur())),
        Provider.GAMMA,
        quotedAt);
  }

  /** Where each required column sits in the header, and how many columns it has. */
  private record Columns(
      int count,
      int airline,
      int flightNumber,
      int from,
      int to,
      int departsAt,
      int arrivesAt,
      int priceEur) {

    static Columns of(List<String> header) {
      return new Columns(
          header.size(),
          required(header, "airline"),
          required(header, "flight_number"),
          required(header, "from"),
          required(header, "to"),
          required(header, "departs_at"),
          required(header, "arrives_at"),
          required(header, "price_eur"));
    }

    private static int required(List<String> header, String name) {
      int index = header.indexOf(name);
      if (index < 0) {
        throw new ProviderUnavailableException(
            Provider.GAMMA,
            FailureReason.MALFORMED_PAGE,
            new IllegalArgumentException("The header has no column " + name));
      }
      return index;
    }
  }
}
