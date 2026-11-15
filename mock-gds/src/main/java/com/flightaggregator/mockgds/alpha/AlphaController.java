package com.flightaggregator.mockgds.alpha;

import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.serving.Paging;
import com.flightaggregator.mockgds.serving.ProviderInventory;
import com.flightaggregator.mockgds.serving.RecordFilter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alpha's endpoint: the inventory, or one origin's legs, or one flight's quote, as a flat JSON list
 * page by page with a cursor.
 */
@RestController
class AlphaController {

  private final ProviderInventory inventory;

  AlphaController(ProviderInventory inventory) {
    this.inventory = inventory;
  }

  @GetMapping(path = "/alpha/legs", produces = MediaType.APPLICATION_JSON_VALUE)
  String legs(
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String limit,
      @RequestParam(required = false) String origin,
      @RequestParam(required = false) String carrier,
      @RequestParam(required = false) String flightNumber,
      @RequestParam(required = false) String destination,
      @RequestParam(required = false) String departure) {
    var size = Paging.limit(limit);
    var offset = cursor == null ? 0 : AlphaCursor.decode(cursor);
    return AlphaJson.write(
        inventory.page(
            Provider.ALPHA,
            RecordFilter.of(origin, carrier, flightNumber, destination, departure),
            offset,
            size));
  }
}
