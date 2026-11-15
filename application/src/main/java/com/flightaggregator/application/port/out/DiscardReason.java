package com.flightaggregator.application.port.out;

/** Why a record of a download was left out of the inventory. */
public enum DiscardReason {
  SHAPE,
  CARRIER,
  FLIGHT_NUMBER,
  AIRPORT,
  TIME,
  PRICE,
  LEG,
  UNKNOWN_AIRPORT,
  CONFLICTING
}
