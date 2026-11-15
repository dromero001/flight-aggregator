package com.flightaggregator.adapter.persistence;

import static java.util.stream.Collectors.joining;

import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.in.SortOrder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * One keyset page of a slot (§5.4), as SQL made of fixed fragments and the values of its {@code ?}
 * parameters: the departures after now (§4.1), each filter present (§4: Max Price inclusive,
 * Carrier on {@code single_carrier}), the rows after the seek position, in the sort key and then
 * id. The sort key and id are the last columns of one of V5's indexes, so the seek and the order
 * are an index scan. The table comes from {@link Slot}; no value is ever written into the text.
 */
record KeysetQuery(String sql, List<Object> parameters) {

  static KeysetQuery of(
      Slot slot, SearchCriteria criteria, Optional<SeekPosition> after, int limit, Instant now) {
    SortKey key = SortKey.of(criteria.sort());
    List<Condition> conditions =
        Stream.of(
                Optional.of(new Condition("departure > ?", ProviderTime.instantParameter(now))),
                criteria.origin().map(origin -> new Condition("origin = ?", origin.code())),
                criteria
                    .destination()
                    .map(destination -> new Condition("destination = ?", destination.code())),
                criteria.maxPrice().map(maxPrice -> new Condition("price <= ?", maxPrice.amount())),
                criteria
                    .carrier()
                    .map(carrier -> new Condition("single_carrier = ?", carrier.code())),
                after.map(
                    position ->
                        new Condition(
                            "(" + key.column() + ", id) > (?, ?)",
                            key.value().apply(position),
                            position.id().value())))
            .flatMap(Optional::stream)
            .toList();
    return new KeysetQuery(
        "SELECT legs FROM "
            + slot.table()
            + " WHERE "
            + conditions.stream().map(Condition::sql).collect(joining(" AND "))
            + " ORDER BY "
            + key.column()
            + ", id LIMIT ?",
        Stream.concat(
                conditions.stream().flatMap(condition -> condition.values().stream()),
                Stream.of(limit))
            .toList());
  }

  /** One fixed fragment of the {@code WHERE} clause, and the values of its parameters. */
  private record Condition(String sql, List<Object> values) {

    Condition(String sql, Object... values) {
      this(sql, List.of(values));
    }
  }

  /** A sort order's column, and its value at a seek position as the column's parameter. */
  private record SortKey(String column, Function<SeekPosition, Object> value) {

    static SortKey of(SortOrder sort) {
      return switch (sort) {
        case PRICE -> new SortKey("price", position -> position.price().amount());
        case DEPARTURE ->
            new SortKey(
                "departure", position -> ProviderTime.instantParameter(position.departure()));
        case DURATION ->
            new SortKey("duration_seconds", position -> position.duration().toSeconds());
      };
    }
  }
}
