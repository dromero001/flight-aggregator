package com.flightaggregator.mockgds.random;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * Derives independent, reproducible random generators from a seed and a key.
 *
 * <p>Every random decision of the dataset (a flight's departure time, whether it operates on a
 * date, its price in a time bucket) uses its own generator derived from the seed and the identity
 * of what is decided. A decision therefore never depends on the order in which things are
 * generated, nor on which other things are generated at all.
 */
public final class SeededRandom {

  private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x100000001b3L;
  private static final char PART_SEPARATOR = '\u001f';

  private SeededRandom() {}

  public static RandomGenerator derive(long seed, String... keyParts) {
    long hash = FNV_OFFSET_BASIS ^ seed;
    for (String part : keyParts) {
      hash = fnv1a(hash, part);
      hash = fnv1a(hash, String.valueOf(PART_SEPARATOR));
    }
    return new SplittableRandom(hash);
  }

  private static long fnv1a(long hash, String text) {
    long result = hash;
    for (int i = 0; i < text.length(); i++) {
      result ^= text.charAt(i);
      result *= FNV_PRIME;
    }
    return result;
  }
}
