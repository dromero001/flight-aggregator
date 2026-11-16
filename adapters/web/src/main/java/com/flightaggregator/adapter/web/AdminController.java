package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.InventoryStatus;
import com.flightaggregator.application.port.in.TriggerIngestion;
import com.flightaggregator.application.port.in.TriggerIngestion.TriggerOutcome;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin side (§6.6): the provider-status screen's data and its "Run ingestion now" button
 * (§6.8). Its paths need the {@code ADMIN} role, which the application's security configuration
 * checks before any request reaches this: it answers 401 without a valid token and 403 without the
 * role, each with a {@code WWW-Authenticate} challenge and no body. The contract names the token: a
 * JWT the identity provider issues, sent as a bearer token.
 */
@RestController
@SecurityScheme(
    name = AdminController.BEARER,
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT")
@SecurityRequirement(name = AdminController.BEARER)
@ApiResponse(responseCode = "401", description = "No valid bearer token", content = @Content)
@ApiResponse(
    responseCode = "403",
    description = "The token does not hold the ADMIN role",
    content = @Content)
public final class AdminController {

  /** The contract's name for the admin side's token. */
  static final String BEARER = "bearer";

  private final InventoryStatus status;
  private final TriggerIngestion ingestion;

  public AdminController(InventoryStatus status, TriggerIngestion ingestion) {
    this.status = status;
    this.ingestion = ingestion;
  }

  @GetMapping(path = "/api/admin/status", produces = MediaType.APPLICATION_JSON_VALUE)
  @ApiResponse(responseCode = "200", description = "The inventory's and each provider's status")
  StatusResponse status() {
    return StatusResponse.of(status.status());
  }

  @PostMapping("/api/admin/ingestions")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @ApiResponse(responseCode = "202", description = "A run started; it goes on in the background")
  @ApiResponse(
      responseCode = "409",
      description = "Another run is going on; try again once it ends",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class)))
  void runIngestion() {
    if (ingestion.trigger() == TriggerOutcome.ALREADY_RUNNING) {
      throw new IngestionAlreadyRunningException();
    }
  }
}
