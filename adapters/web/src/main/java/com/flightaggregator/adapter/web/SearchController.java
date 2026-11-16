package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.RateLimitedAction;
import com.flightaggregator.application.port.in.SearchItineraries;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public search (§4, §5.4, §6.6). Each parameter is read as the text the client sent and parsed
 * by {@link SearchParameters}, so a bad value is a 400 naming it, as is a parameter the search does
 * not take, read from the request itself; the contract documents each one with that parser's own
 * patterns and bounds. They are named request parameters, not one bound object, because Spring 7
 * binds request headers into such an object too.
 */
@RestController
public final class SearchController {

  private final SearchItineraries search;

  public SearchController(SearchItineraries search) {
    this.search = search;
  }

  @GetMapping(path = "/api/itineraries", produces = MediaType.APPLICATION_JSON_VALUE)
  @RateLimited(RateLimitedAction.SEARCH)
  @ApiResponse(responseCode = "200", description = "One page of itineraries")
  @ApiResponse(
      responseCode = "400",
      description = "A parameter or the cursor is not valid; the detail names it",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "The search took too long to read; try again after Retry-After",
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
  SearchResponse search(
      @Parameter(
              description = "The airport the trip departs from: a three-letter code, in any case",
              schema = @Schema(pattern = SearchParameters.AIRPORT))
          @RequestParam(name = "origin", required = false)
          String origin,
      @Parameter(
              description = "The airport the trip arrives at: a three-letter code, in any case",
              schema = @Schema(pattern = SearchParameters.AIRPORT))
          @RequestParam(name = "destination", required = false)
          String destination,
      @Parameter(
              description = "The highest final price in euros, inclusive, above 0",
              schema = @Schema(pattern = SearchParameters.AMOUNT))
          @RequestParam(name = "maxPrice", required = false)
          String maxPrice,
      @Parameter(
              description =
                  "The airline that operates every leg: a two-character code, in any case",
              schema = @Schema(pattern = SearchParameters.CARRIER))
          @RequestParam(name = "carrier", required = false)
          String carrier,
      @Parameter(
              description = "The order of the results",
              schema =
                  @Schema(
                      allowableValues = {
                        SearchParameters.PRICE,
                        SearchParameters.DEPARTURE,
                        SearchParameters.DURATION
                      },
                      defaultValue = SearchParameters.DEFAULT_SORT))
          @RequestParam(name = "sort", required = false)
          String sort,
      @Parameter(
              description = "How many itineraries the page holds at most",
              schema =
                  @Schema(
                      type = "integer",
                      format = "int32",
                      minimum = "1",
                      maximum = "" + SearchParameters.MAXIMUM_LIMIT,
                      defaultValue = "" + SearchParameters.DEFAULT_LIMIT))
          @RequestParam(name = "limit", required = false)
          String limit,
      @Parameter(
              description = "The previous page's nextCursor, to read the page after it",
              schema = @Schema(maxLength = CursorCodec.MAXIMUM_LENGTH))
          @RequestParam(name = "cursor", required = false)
          String cursor,
      HttpServletRequest request) {
    SearchParameters.refuseUnknown(request.getParameterMap().keySet());
    SearchParameters parameters =
        new SearchParameters(origin, destination, carrier, maxPrice, sort, limit, cursor);
    return SearchResponse.of(search.search(parameters.toQuery()));
  }
}
