package com.flightaggregator.domain;

/**
 * The three mock flight providers. The declaration order breaks full ties in deduplication, so it
 * is part of the behaviour.
 */
public enum Provider {
  ALPHA,
  BETA,
  GAMMA
}
