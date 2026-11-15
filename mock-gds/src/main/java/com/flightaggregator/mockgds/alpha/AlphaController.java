package com.flightaggregator.mockgds.alpha;

import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.serving.ProviderInventory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Alpha's endpoint: the whole inventory as a flat JSON list, page by page with a cursor. */
@RestController
class AlphaController {

  private static final int MAX_LIMIT = 1000;

  private final ProviderInventory inventory;

  AlphaController(ProviderInventory inventory) {
    this.inventory = inventory;
  }

  @GetMapping(path = "/alpha/legs", produces = MediaType.APPLICATION_JSON_VALUE)
  String legs(
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "500") int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Limit must be between 1 and " + MAX_LIMIT);
    }
    var offset = cursor == null ? 0 : offsetOf(cursor);
    return AlphaJson.write(inventory.page(Provider.ALPHA, offset, limit));
  }

  private static int offsetOf(String cursor) {
    try {
      return AlphaCursor.decode(cursor);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unreadable cursor", e);
    }
  }
}
