package com.flightaggregator.mockgds.beta;

import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.serving.Paging;
import com.flightaggregator.mockgds.serving.ProviderInventory;
import com.flightaggregator.mockgds.serving.RecordFilter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Beta's endpoint: the inventory, or one origin's legs, or one flight's quote, as nested XML page
 * by page with a page number.
 */
@RestController
class BetaController {

  private final ProviderInventory inventory;

  BetaController(ProviderInventory inventory) {
    this.inventory = inventory;
  }

  @GetMapping(path = "/beta/segments", produces = MediaType.APPLICATION_XML_VALUE)
  String segments(
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String pageSize,
      @RequestParam(required = false) String origin,
      @RequestParam(required = false) String carrier,
      @RequestParam(required = false) String flightNumber,
      @RequestParam(required = false) String destination,
      @RequestParam(required = false) String departure) {
    var size = Paging.pageSize(pageSize);
    var number = page == null ? 1 : Paging.integer("Page", page, 1);
    var offset = (int) Math.min((long) (number - 1) * size, Integer.MAX_VALUE);
    var result =
        inventory.page(
            Provider.BETA,
            RecordFilter.of(origin, carrier, flightNumber, destination, departure),
            offset,
            size);
    var totalPages = Math.max(1, (result.total() + size - 1) / size);
    return BetaXml.write(result, number, totalPages);
  }
}
