package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.RateLimitedAction;
import com.flightaggregator.application.port.in.RepriceItinerary;
import com.flightaggregator.application.port.in.RepriceResult;
import com.flightaggregator.domain.ItineraryId;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public re-price on selection (§6.3, §6.5, §6.6), as Q4-A answers it: the choice confirmed, at
 * its price or a new one, or the best alternative, each a 200 the dashboard renders as a state;
 * nothing confirmable is a 503 to retry; an id the inventory does not hold is a 404, and one that
 * is not an id a 400. The id is read as the text the client sent, as the search reads its
 * parameters.
 */
@RestController
public final class RepriceController {

  /** An itinerary id as a search writes it: a UUID in its canonical form, in either case. */
  static final String ID =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  /** §6.5's text, word for word. */
  static final String UNCONFIRMABLE =
      "We can't confirm the price right now. Please try again in a few minutes.";

  /** Q9: the providers' circuit breakers stay open 60 s before they probe again. */
  private static final Duration RETRY_UNCONFIRMABLE = Duration.ofSeconds(60);

  private final RepriceItinerary reprice;

  public RepriceController(RepriceItinerary reprice) {
    this.reprice = reprice;
  }

  @PostMapping(path = "/api/itineraries/{id}/reprice", produces = MediaType.APPLICATION_JSON_VALUE)
  @RateLimited(RateLimitedAction.REPRICE)
  @ApiResponse(
      responseCode = "200",
      description = "The choice confirmed, at its price or a new one, or the best alternative")
  @ApiResponse(
      responseCode = "400",
      description = "The id is not an itinerary id",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No itinerary with this id is on sale; search again",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "429",
      description = "Too many re-prices from this address; try again after Retry-After",
      headers =
          @Header(
              name = HttpHeaders.RETRY_AFTER,
              required = true,
              description = "How many seconds to wait before asking again",
              schema = @Schema(type = "integer", minimum = "0")),
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Nothing could be confirmed; try again after Retry-After",
      headers =
          @Header(
              name = HttpHeaders.RETRY_AFTER,
              required = true,
              description = "How many seconds to wait before asking again",
              schema = @Schema(type = "integer", minimum = "0")),
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class)))
  RepriceResponse reprice(
      @Parameter(
              description = "The id of the chosen itinerary, as the search gave it",
              schema = @Schema(format = "uuid", pattern = ID))
          @PathVariable("id")
          String id) {
    return switch (reprice.reprice(itineraryId(id))) {
      case RepriceResult.Confirmed(var itinerary) -> RepriceResponse.confirmed(itinerary);
      case RepriceResult.PriceChanged(var itinerary, var previousPrice) ->
          RepriceResponse.priceChanged(itinerary, previousPrice);
      case RepriceResult.Alternative(var itinerary) -> RepriceResponse.alternative(itinerary);
      case RepriceResult.Unconfirmable() ->
          throw new RetryLaterException(
              HttpStatus.SERVICE_UNAVAILABLE, UNCONFIRMABLE, RETRY_UNCONFIRMABLE);
      case RepriceResult.NotFound() -> throw new ItineraryNotFoundException();
    };
  }

  /** After the pattern, {@code UUID.fromString} cannot throw. */
  private static ItineraryId itineraryId(String id) {
    if (!id.matches(ID)) {
      throw new InvalidParameterException(
          "id must be an itinerary id as a search gives it, like 3f2c8f4e-6b1a-3c55-9d27-0a8e4b6c1f90");
    }
    return new ItineraryId(UUID.fromString(id));
  }
}
