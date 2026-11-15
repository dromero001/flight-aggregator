package com.flightaggregator.mockgds.serving;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A test-only endpoint behind each provider's gate. The real controllers come in later tasks. */
@RestController
class ProbeController {

  @GetMapping({"/alpha/probe", "/beta/probe", "/gamma/probe"})
  String probe() {
    return "reached";
  }

  /** Outside the provider prefixes: it must stay reachable without a key. */
  @GetMapping("/probe")
  String ungatedProbe() {
    return "open";
  }
}
