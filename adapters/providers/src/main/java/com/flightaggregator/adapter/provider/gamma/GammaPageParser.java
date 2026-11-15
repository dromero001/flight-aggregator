package com.flightaggregator.adapter.provider.gamma;

import com.flightaggregator.adapter.provider.LoggedText;
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

  private static final String EOL = "\r\n";
  private static final String QUOTE = "\"";

  private GammaPageParser() {}

  static ParsedPage parse(String body, Instant quotedAt, int offset, int limit) {
    String[] lines = lines(body);
    Columns columns = Columns.of(lines[0]);
    int rows = lines.length - 1;
    if (rows > limit) {
      throw malformed("Asked for at most " + limit + " rows, got " + rows);
    }
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
        rows == limit ? Optional.of(Integer.toString(offset + limit)) : Optional.empty();
    return new ParsedPage(legs, discarded, next);
  }

  private static ProviderUnavailableException malformed(String detail) {
    return new ProviderUnavailableException(
        Provider.GAMMA, FailureReason.MALFORMED_PAGE, new IllegalArgumentException(detail));
  }

  /** Exactly one final line end closes the last line; every other line counts, a blank one too. */
  private static String[] lines(String body) {
    String closed = body.endsWith(EOL) ? body.substring(0, body.length() - EOL.length()) : body;
    String[] lines = closed.split(EOL, -1);
    for (String line : lines) {
      if (line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0) {
        throw malformed("A line break that is not CRLF, so the records cannot be told apart");
      }
    }
    return lines;
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

    static Columns of(String line) {
      List<String> header = List.of(line.split(",", -1));
      return new Columns(
          header.size(),
          required(line, header, "airline"),
          required(line, header, "flight_number"),
          required(line, header, "from"),
          required(line, header, "to"),
          required(line, header, "departs_at"),
          required(line, header, "arrives_at"),
          required(line, header, "price_eur"));
    }

    private static int required(String line, List<String> header, String name) {
      int index = header.indexOf(name);
      if (index < 0) {
        throw malformed("The header " + LoggedText.quoted(line) + " has no column " + name);
      }
      if (index != header.lastIndexOf(name)) {
        throw malformed("The header " + LoggedText.quoted(line) + " repeats the column " + name);
      }
      return index;
    }
  }
}
