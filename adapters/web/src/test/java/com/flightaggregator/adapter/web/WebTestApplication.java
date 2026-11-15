package com.flightaggregator.adapter.web;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The configuration a {@code @WebMvcTest} of this module starts from: the module has no application
 * of its own (bootstrap wires the controllers), and the slice looks for one in the test's package
 * or above it.
 */
@SpringBootApplication
class WebTestApplication {}
