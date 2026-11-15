package com.flightaggregator.adapter.provider;

import java.util.Optional;

/** How one provider pages: the request of the page at a position, empty for the first page. */
@FunctionalInterface
interface PageFormat {

  PageRequest at(Optional<String> position, int pageSize);
}
