package com.flightaggregator.adapter.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * The contract's {@code ProblemDetail} as the API writes it (RFC 9457), in place of the one
 * springdoc reads from Spring's class, which lists a {@code properties} member Spring never writes
 * and a {@code type} the API never sets (G1, the user's decision of 2026-11-16). Every problem the
 * API writes, its own or Spring MVC's, holds these four members.
 */
public final class ProblemDetailSchema implements OpenApiCustomizer {

  /** The name every problem response refers to, {@code #/components/schemas/ProblemDetail}. */
  static final String NAME = "ProblemDetail";

  @Override
  public void customise(OpenAPI openApi) {
    Schema<Object> status = member("integer", "The HTTP status code");
    status.setFormat("int32");
    Schema<Object> instance = member("string", "The path of the request, such as /api/itineraries");
    instance.setFormat("uri-reference");

    Schema<Object> problem = new Schema<>();
    problem.setTypes(Set.of("object"));
    problem.setDescription("An RFC 9457 problem: what went wrong with the request");
    problem.addProperty(
        "title", member("string", "The status's reason phrase, such as \"Bad Request\""));
    problem.addProperty("status", status);
    problem.addProperty("detail", member("string", "What went wrong, and what to do about it"));
    problem.addProperty("instance", instance);
    problem.setRequired(List.copyOf(problem.getProperties().keySet()));
    openApi.getComponents().addSchemas(NAME, problem);
  }

  private static Schema<Object> member(String type, String description) {
    Schema<Object> member = new Schema<>();
    member.setTypes(Set.of(type));
    member.setDescription(description);
    return member;
  }
}
