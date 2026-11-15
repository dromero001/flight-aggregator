package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.web.ProblemResponses;
import com.flightaggregator.adapter.web.SearchController;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The HTTP adapter: its controllers, over the inbound ports, and the problem responses of their
 * errors. The adapter's package is not scanned, so each class is named here, as the stores are in
 * {@code PersistenceConfig}.
 */
@Configuration(proxyBeanMethods = false)
@Import({SearchController.class, ProblemResponses.class})
class WebConfig {}
