/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3;

import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.ByteString;

import static org.junit.Assert.fail;

/** Tests how each code point is encoded and decoded in the context of each URL component. */
class UrlComponentEncodingTester {
  private static final int UNICODE_2 = 0x07ff; // Arbitrary code point that's 2 bytes in UTF-8.
  private static final int UNICODE_3 = 0xffff; // Arbitrary code point that's 3 bytes in UTF-8.
  private static final int UNICODE_4 = 0x10ffff; // Arbitrary code point that's 4 bytes in UTF-8.

  /**
   * The default encode set for the ASCII range. The specific rules vary per-component: for example,
   * '?' may be identity-encoded in a fragment, but must be percent-encoded in a path.
   *
   * See https://url.spec.whatwg.org/#percent-encoded-bytes
   */
  private static final Map<Integer, Encoding> defaultEncodings;

  static {
    Map<Integer, Encoding> map = new LinkedHashMap<>();
    map.put(       0x0, Encoding.PERCENT); // Null character
    map.put(       0x1, Encoding.PERCENT); // Start of Header
    map.put(       0x2, Encoding.PERCENT); // Start of Text
    map.put(       0x3, Encoding.PERCENT); // End of Text
    map.put(       0x4, Encoding.PERCENT); // End of Transmission
    map.put(       0x5, Encoding.PERCENT); // Enquiry
    map.put(       0x6, Encoding.PERCENT); // Acknowledgment
    map.put(       0x7, Encoding.PERCENT); // Bell
    map.put((int) '\b', Encoding.PERCENT); // Backspace
    map.put((int) '\t', Encoding.SKIP);    // Horizontal Tab
    map.put((int) '\n', Encoding.SKIP);    // Line feed
    map.put(       0xb, Encoding.PERCENT); // Vertical Tab
    map.put((int) '\f', Encoding.SKIP);    // Form feed
    map.put((int) '\r', Encoding.SKIP);    // Carriage return
    map.put(       0xe, Encoding.PERCENT); // Shift Out
    map.put(       0xf, Encoding.PERCENT); // Shift In
    map.put(      0x10, Encoding.PERCENT); // Data Link Escape
    map.put(      0x11, Encoding.PERCENT); // Device Control 1 (oft. XON)
    map.put(      0x12, Encoding.PERCENT); // Device Control 2
    map.put(      0x13, Encoding.PERCENT); // Device Control 3 (oft. XOFF)
    map.put(      0x14, Encoding.PERCENT); // Device Control 4
    map.put(      0x15, Encoding.PERCENT); // Negative Acknowledgment
    map.put(      0x16, Encoding.PERCENT); // Synchronous idle
    map.put(      0x17, Encoding.PERCENT); // End of Transmission Block
    map.put(      0x18, Encoding.PERCENT); // Cancel
    map.put(      0x19, Encoding.PERCENT); // End of Medium
    map.put(      0x1a, Encoding.PERCENT); // Substitute
    map.put(      0x1b, Encoding.PERCENT); // Escape
    map.put(      0x1c, Encoding.PERCENT); // File Separator
    map.put(      0x1d, Encoding.PERCENT); // Group Separator
    map.put(      0x1e, Encoding.PERCENT); // Record Separator
    map.put(      0x1f, Encoding.PERCENT); // Unit Separator
    map.put((int)  ' ', Encoding.PERCENT);
    map.put((int)  '!', Encoding.IDENTITY);
    map.put((int)  '"', Encoding.PERCENT);
    map.put((int)  '#', Encoding.PERCENT);
    map.put((int)  '$', Encoding.IDENTITY);
    map.put((int)  '%', Encoding.IDENTITY);
    map.put((int)  '&', Encoding.IDENTITY);
    map.put((int) '\'', Encoding.IDENTITY);
    map.put((int)  '(', Encoding.IDENTITY);
    map.put((int)  ')', Encoding.IDENTITY);
    map.put((int)  '*', Encoding.IDENTITY);
    map.put((int)  '+', Encoding.IDENTITY);
    map.put((int)  ',', Encoding.IDENTITY);
    map.put((int)  '-', Encoding.IDENTITY);
    map.put((int)  '.', Encoding.IDENTITY);
    map.put((int)  '/', Encoding.IDENTITY);
    map.put((int)  '0', Encoding.IDENTITY);
    map.put((int)  '1', Encoding.IDENTITY);
    map.put((int)  '2', Encoding.IDENTITY);
    map.put((int)  '3', Encoding.IDENTITY);
    map.put((int)  '4', Encoding.IDENTITY);
    map.put((int)  '5', Encoding.IDENTITY);
    map.put((int)  '6', Encoding.IDENTITY);
    map.put((int)  '7', Encoding.IDENTITY);
    map.put((int)  '8', Encoding.IDENTITY);
    map.put((int)  '9', Encoding.IDENTITY);
    map.put((int)  ':', Encoding.IDENTITY);
    map.put((int)  ';', Encoding.IDENTITY);
    map.put((int)  '<', Encoding.PERCENT);
    map.put((int)  '=', Encoding.IDENTITY);
    map.put((int)  '>', Encoding.PERCENT);
    map.put((int)  '?', Encoding.PERCENT);
    map.put((int)  '@', Encoding.IDENTITY);
    map.put((int)  'A', Encoding.IDENTITY);
    map.put((int)  'B', Encoding.IDENTITY);
    map.put((int)  'C', Encoding.IDENTITY);
    map.put((int)  'D', Encoding.IDENTITY);
    map.put((int)  'E', Encoding.IDENTITY);
    map.put((int)  'F', Encoding.IDENTITY);
    map.put((int)  'G', Encoding.IDENTITY);
    map.put((int)  'H', Encoding.IDENTITY);
    map.put((int)  'I', Encoding.IDENTITY);
    map.put((int)  'J', Encoding.IDENTITY);
    map.put((int)  'K', Encoding.IDENTITY);
    map.put((int)  'L', Encoding.IDENTITY);
    map.put((int)  'M', Encoding.IDENTITY);
    map.put((int)  'N', Encoding.IDENTITY);
    map.put((int)  'O', Encoding.IDENTITY);
    map.put((int)  'P', Encoding.IDENTITY);
    map.put((int)  'Q', Encoding.IDENTITY);
    map.put((int)  'R', Encoding.IDENTITY);
    map.put((int)  'S', Encoding.IDENTITY);
    map.put((int)  'T', Encoding.IDENTITY);
    map.put((int)  'U', Encoding.IDENTITY);
    map.put((int)  'V', Encoding.IDENTITY);
    map.put((int)  'W', Encoding.IDENTITY);
    map.put((int)  'X', Encoding.IDENTITY);
    map.put((int)  'Y', Encoding.IDENTITY);
    map.put((int)  'Z', Encoding.IDENTITY);
    map.put((int)  '[', Encoding.IDENTITY);
    map.put((int) '\\', Encoding.IDENTITY);
    map.put((int)  ']', Encoding.IDENTITY);
    map.put((int)  '^', Encoding.IDENTITY);
    map.put((int)  '_', Encoding.IDENTITY);
    map.put((int)  '`', Encoding.PERCENT);
    map.put((int)  'a', Encoding.IDENTITY);
    map.put((int)  'b', Encoding.IDENTITY);
    map.put((int)  'c', Encoding.IDENTITY);
    map.put((int)  'd', Encoding.IDENTITY);
    map.put((int)  'e', Encoding.IDENTITY);
    map.put((int)  'f', Encoding.IDENTITY);
    map.put((int)  'g', Encoding.IDENTITY);
    map.put((int)  'h', Encoding.IDENTITY);
    map.put((int)  'i', Encoding.IDENTITY);
    map.put((int)  'j', Encoding.IDENTITY);
    map.put((int)  'k', Encoding.IDENTITY);
    map.put((int)  'l', Encoding.IDENTITY);
    map.put((int)  'm', Encoding.IDENTITY);
    map.put((int)  'n', Encoding.IDENTITY);
    map.put((int)  'o', Encoding.IDENTITY);
    map.put((int)  'p', Encoding.IDENTITY);
    map.put((int)  'q', Encoding.IDENTITY);
    map.put((int)  'r', Encoding.IDENTITY);
    map.put((int)  's', Encoding.IDENTITY);
    map.put((int)  't', Encoding.IDENTITY);
    map.put((int)  'u', Encoding.IDENTITY);
    map.put((int)  'v', Encoding.IDENTITY);
    map.put((int)  'w', Encoding.IDENTITY);
    map.put((int)  'x', Encoding.IDENTITY);
    map.put((int)  'y', Encoding.IDENTITY);
    map.put((int)  'z', Encoding.IDENTITY);
    map.put((int)  '{', Encoding.IDENTITY);
    map.put((int)  '|', Encoding.IDENTITY);
    map.put((int)  '}', Encoding.IDENTITY);
    map.put((int)  '~', Encoding.IDENTITY);
    map.put(      0x7f, Encoding.PERCENT); // Delete
    map.put( UNICODE_2, Encoding.PERCENT);
    map.put( UNICODE_3, Encoding.PERCENT);
    map.put( UNICODE_4, Encoding.PERCENT);
    defaultEncodings = Collections.unmodifiableMap(map);
  }

  private final Map<Integer, Encoding> encodings;
  private final StringBuilder uriEscapedCodePoints = new StringBuilder();

  public UrlComponentEncodingTester() {
    this.encodings = new LinkedHashMap<>(defaultEncodings);
  }

  public UrlComponentEncodingTester override(Encoding encoding, int... codePoints) {
    for (int codePoint : codePoints) {
      encodings.put(codePoint, encoding);
    }
    return this;
  }

  public UrlComponentEncodingTester identityForNonAscii() {
    encodings.put(UNICODE_2, Encoding.IDENTITY);
    encodings.put(UNICODE_3, Encoding.IDENTITY);
    encodings.put(UNICODE_4, Encoding.IDENTITY);
    return this;
  }

  /**
   * Configure a character to be skipped but only for conversion to and from {@code java.net.URI}.
   * That class is more strict than the others.
   */
  public UrlComponentEncodingTester skipForUri(int... codePoints) {
    uriEscapedCodePoints.append(new String(codePoints, 0, codePoints.length));
    return this;
  }

  public UrlComponentEncodingTester test(Component component) {
    for (Map.Entry<Integer, Encoding> entry : encodings.entrySet()) {
      Encoding encoding = entry.getValue();
      int codePoint = entry.getKey();
      testEncodeAndDecode(codePoint, component);
      if (encoding == Encoding.SKIP) continue;

      testParseOriginal(codePoint, encoding, component);
      testParseAlreadyEncoded(codePoint, encoding, component);
      testToUrl(codePoint, encoding, component);
      testFromUrl(codePoint, encoding, component);

      if (codePoint != '%') {
        boolean uriEscaped = uriEscapedCodePoints.indexOf(
            Encoding.IDENTITY.encode(codePoint)) != -1;
        testUri(codePoint, encoding, component, uriEscaped);
      }
    }
    return this;
  }

  private void testParseAlreadyEncoded(int codePoint, Encoding encoding, Component component) {
    String encoded = encoding.encode(codePoint);
    String urlString = component.urlString(encoded);
    HttpUrl url = HttpUrl.get(urlString);
    if (!component.encodedValue(url).equals(encoded)) {
      fail(Util.format("Encoding %s %#x using %s", component, codePoint, encoding));
    }
  }

  private void testEncodeAndDecode(int codePoint, Component component) {
    String expected = Encoding.IDENTITY.encode(codePoint);
    HttpUrl.Builder builder = HttpUrl.get("http://host/").newBuilder();
    component.set(builder, expected);
    HttpUrl url = builder.build();
    String actual = component.get(url);
    if (!expected.equals(actual)) {
      fail(Util.format("Roundtrip %s %#x %s", component, codePoint, url));
    }
  }

  private void testParseOriginal(int codePoint, Encoding encoding, Component component) {
    String encoded = encoding.encode(codePoint);
    if (encoding != Encoding.PERCENT) return;
    String identity = Encoding.IDENTITY.encode(codePoint);
    String urlString = component.urlString(identity);
    HttpUrl url = HttpUrl.get(urlString);

    String s = component.encodedValue(url);
    if (!s.equals(encoded)) {
      fail(Util.format("Encoding %s %#02x using %s", component, codePoint, encoding));
    }
  }

  private void testToUrl(int codePoint, Encoding encoding, Component component) {
    String encoded = encoding.encode(codePoint);
    HttpUrl httpUrl = HttpUrl.get(component.urlString(encoded));
    URL javaNetUrl = httpUrl.url();
    if (!javaNetUrl.toString().equals(javaNetUrl.toString())) {
      fail(Util.format("Encoding %s %#x using %s", component, codePoint, encoding));
    }
  }

  private void testFromUrl(int codePoint, Encoding encoding, Component component) {
    String encoded = encoding.encode(codePoint);
    HttpUrl httpUrl = HttpUrl.get(component.urlString(encoded));
    HttpUrl toAndFromJavaNetUrl = HttpUrl.get(httpUrl.url());
    if (!toAndFromJavaNetUrl.equals(httpUrl)) {
      fail(Util.format("Encoding %s %#x using %s", component, codePoint, encoding));
    }
  }

  private void testUri(
      int codePoint, Encoding encoding, Component component, boolean uriEscaped) {
    String string = new String(new int[] {codePoint}, 0, 1);
    String encoded = encoding.encode(codePoint);
    HttpUrl httpUrl = HttpUrl.get(component.urlString(encoded));
    URI uri = httpUrl.uri();
    HttpUrl toAndFromUri = HttpUrl.get(uri);
    if (uriEscaped) {
      // The URI has more escaping than the HttpURL. Check that the decoded values still match.
      if (uri.toString().equals(httpUrl.toString())) {
        fail(Util.format("Encoding %s %#x using %s", component, codePoint, encoding));
      }
      if (!component.get(toAndFromUri).equals(string)) {
        fail(Util.format("Encoding %s %#x using %s", component, codePoint, encoding));
      }
    } else {
      // Check that the URI and HttpURL have the exact same escaping.
      if (!toAndFromUri.equals(httpUrl)) {
        fail(Util.format("Encoding %s %#x using %s", component, codePoint, encoding));
      }
      if (!uri.toString().equals(httpUrl.toString())) {
        fail(Util.format("Encoding %s %#x using %s", component, codePoint, encoding));
      }
    }
  }

  public enum Encoding {
    IDENTITY {
      @Override public String encode(int codePoint) {
        return new String(new int[] {codePoint}, 0, 1);
      }
    },

    PERCENT {
      @Override public String encode(int codePoint) {
        ByteString utf8 = ByteString.encodeUtf8(IDENTITY.encode(codePoint));
        Buffer percentEncoded = new Buffer();
        for (int i = 0; i < utf8.size(); i++) {
          percentEncoded.writeUtf8(Util.format("%%%02X", utf8.getByte(i) & 0xff));
        }
        return percentEncoded.readUtf8();
      }
    },

    SKIP;

    public String encode(int codePoint) {
      throw new UnsupportedOperationException();
    }
  }

  public enum Component {
    USER {
      @Override public String urlString(String value) {
        return "http://" + value + "@example.com/";
      }

      @Override public String encodedValue(HttpUrl url) {
        return url.encodedUsername();
      }

      @Override public void set(HttpUrl.Builder builder, String value) {
        builder.username(value);
      }

      @Override public String get(HttpUrl url) {
        return url.username();
      }
    },
    PASSWORD {
      @Override public String urlString(String value) {
        return "http://:" + value + "@example.com/";
      }

      @Override public String encodedValue(HttpUrl url) {
        return url.encodedPassword();
      }

      @Override public void set(HttpUrl.Builder builder, String value) {
        builder.password(value);
      }

      @Override public String get(HttpUrl url) {
        return url.password();
      }
    },
    PATH {
      @Override public String urlString(String value) {
        return "http://example.com/a" + value + "z/";
      }

      @Override public String encodedValue(HttpUrl url) {
        String path = url.encodedPath();
        return path.substring(2, path.length() - 2);
      }

      @Override public void set(HttpUrl.Builder builder, String value) {
        builder.addPathSegment("a" + value + "z");
      }

      @Override public String get(HttpUrl url) {
        String pathSegment = url.pathSegments().get(0);
        return pathSegment.substring(1, pathSegment.length() - 1);
      }
    },
    QUERY {
      @Override public String urlString(String value) {
        return "http://example.com/?a" + value + "z";
      }

      @Override public String encodedValue(HttpUrl url) {
        String query = url.encodedQuery();
        return query.substring(1, query.length() - 1);
      }

      @Override public void set(HttpUrl.Builder builder, String value) {
        builder.query("a" + value + "z");
      }

      @Override public String get(HttpUrl url) {
        String query = url.query();
        return query.substring(1, query.length() - 1);
      }
    },
    QUERY_VALUE {
      @Override public String urlString(String value) {
        return "http://example.com/?q=a" + value + "z";
      }

      @Override public String encodedValue(HttpUrl url) {
        String query = url.encodedQuery();
        return query.substring(3, query.length() - 1);
      }

      @Override public void set(HttpUrl.Builder builder, String value) {
        builder.addQueryParameter("q", "a" + value + "z");
      }

      @Override public String get(HttpUrl url) {
        String value = url.queryParameter("q");
        return value.substring(1, value.length() - 1);
      }
    },
    FRAGMENT {
      @Override public String urlString(String value) {
        return "http://example.com/#a" + value + "z";
      }

      @Override public String encodedValue(HttpUrl url) {
        String fragment = url.encodedFragment();
        return fragment.substring(1, fragment.length() - 1);
      }

      @Override public void set(HttpUrl.Builder builder, String value) {
        builder.fragment("a" + value + "z");
      }

      @Override public String get(HttpUrl url) {
        String fragment = url.fragment();
        return fragment.substring(1, fragment.length() - 1);
      }
    };

    public abstract String urlString(String value);

    public abstract String encodedValue(HttpUrl url);

    public abstract void set(HttpUrl.Builder builder, String value);

    public abstract String get(HttpUrl url);
  }
}
