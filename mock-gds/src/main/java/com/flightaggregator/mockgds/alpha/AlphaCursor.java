package com.flightaggregator.mockgds.alpha;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Alpha's opaque cursor: the next offset as decimal text, in unpadded Base64URL. */
final class AlphaCursor {

  private AlphaCursor() {}

  static String encode(int offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.US_ASCII));
  }

  static int decode(String cursor) {
    var text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
    var offset = Integer.parseInt(text);
    if (offset < 0) {
      throw new IllegalArgumentException("Cursor must hold a non-negative offset");
    }
    return offset;
  }
}
