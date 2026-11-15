package com.flightaggregator.mockgds.alpha;

import com.flightaggregator.mockgds.serving.InvalidRequestException;
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

  /** The offset a cursor holds; any cursor {@link #encode} would not have produced is refused. */
  static int decode(String cursor) {
    try {
      var offset =
          Integer.parseInt(
              new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
      if (offset >= 0 && encode(offset).equals(cursor)) {
        return offset;
      }
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("Unreadable cursor", e);
    }
    throw new InvalidRequestException("Unreadable cursor");
  }
}
