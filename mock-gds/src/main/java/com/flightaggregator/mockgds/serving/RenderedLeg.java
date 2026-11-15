package com.flightaggregator.mockgds.serving;

import com.flightaggregator.mockgds.dataset.Defect;
import com.flightaggregator.mockgds.dataset.MalformedLeg;
import java.math.BigDecimal;

/**
 * The values a serializer writes for one record, with its defect already applied. Each format only
 * decides how to write them.
 *
 * @param flightNumber {@code null} when the record omits it
 */
public record RenderedLeg(
    String carrier,
    Integer flightNumber,
    String origin,
    String destination,
    String departureTime,
    String arrivalTime,
    BigDecimal price) {

  public static RenderedLeg of(PricedRecord priced) {
    var leg = priced.record().leg();
    var defect = priced.record() instanceof MalformedLeg malformed ? malformed.defect() : null;
    return new RenderedLeg(
        leg.airline(),
        defect == Defect.MISSING_FIELD ? null : leg.flightNumber(),
        leg.origin(),
        defect == Defect.UNKNOWN_AIRPORT ? Defect.UNKNOWN_AIRPORT_CODE : leg.destination(),
        defect == Defect.BAD_DATE ? Defect.INVALID_DATE_TEXT : IsoTimes.format(leg.departure()),
        IsoTimes.format(leg.arrival()),
        defect == Defect.NEGATIVE_PRICE ? priced.price().negate() : priced.price());
  }
}
