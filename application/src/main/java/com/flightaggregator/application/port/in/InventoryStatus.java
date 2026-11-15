package com.flightaggregator.application.port.in;

/** The inventory's and each provider's status, for the admin (§6.6, §6.8). */
public interface InventoryStatus {

  StatusReport status();
}
