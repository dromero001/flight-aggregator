package com.flightaggregator.mockgds.gate;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A test-only endpoint behind each provider's gate. */
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
