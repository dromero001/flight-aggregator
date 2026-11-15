package com.flightaggregator.mockgds.gamma;

import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.serving.ListingParams;
import com.flightaggregator.mockgds.serving.Paging;
import com.flightaggregator.mockgds.serving.ProviderInventory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gamma's endpoint: the inventory, or one origin's legs, or one flight's quote, as CSV page by page
 * with an offset and a limit.
 */
@RestController
class GammaController {

  private final ProviderInventory inventory;

  GammaController(ProviderInventory inventory) {
    this.inventory = inventory;
  }

  @GetMapping(path = "/gamma/legs.csv", produces = "text/csv;charset=UTF-8")
  String legs(
      @RequestParam(required = false) String offset,
      @RequestParam(required = false) String limit,
      @ModelAttribute ListingParams listing) {
    var size = Paging.limit(limit);
    var start = offset == null ? 0 : Paging.integer("Offset", offset, 0);
    return GammaCsv.write(inventory.page(Provider.GAMMA, listing.toFilter(), start, size));
  }
}
