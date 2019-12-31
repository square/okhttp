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
import java.util.LinkedHashMap;
import java.util.Locale;
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

  private final Map<Integer, Encoding> encodings = new LinkedHashMap<>();
  private final StringBuilder uriEscapedCodePoints = new StringBuilder();
  private final StringBuilder uriStrippedCodePoints = new StringBuilder();

  private UrlComponentEncodingTester() {
  }

  /**
   * Returns a new instance configured with a default encode set for the ASCII range. The specific
   * rules vary per-component: for example, '?' may be identity-encoded in a fragment, but must be
   * percent-encoded in a path.
   *
   * See https://url.spec.whatwg.org/#percent-encoded-bytes
   */
  public static UrlComponentEncodingTester newInstance() {
    return new UrlComponentEncodingTester()
        .allAscii(Encoding.IDENTITY)
        .nonPrintableAscii(Encoding.PERCENT)
        .override(Encoding.SKIP, '\t', '\n', '\f', '\r')
        .override(Encoding.PERCENT, ' ', '"', '#', '<', '>', '?', '`')
        .override(Encoding.PERCENT, UNICODE_2, UNICODE_3, UNICODE_4);
  }

  private UrlComponentEncodingTester allAscii(Encoding encoding) {
    for (int i = 0; i < 128; i++) {
      encodings.put(i, encoding);
    }
    return this;
  }

  public UrlComponentEncodingTester override(Encoding encoding, int... codePoints) {
    for (int codePoint : codePoints) {
      encodings.put(codePoint, encoding);
    }
    return this;
  }

  public UrlComponentEncodingTester nonPrintableAscii(Encoding encoding) {
    encodings.put(       0x0, encoding); // Null character
    encodings.put(       0x1, encoding); // Start of Header
    encodings.put(       0x2, encoding); // Start of Text
    encodings.put(       0x3, encoding); // End of Text
    encodings.put(       0x4, encoding); // End of Transmission
    encodings.put(       0x5, encoding); // Enquiry
    encodings.put(       0x6, encoding); // Acknowledgment
    encodings.put(       0x7, encoding); // Bell
    encodings.put((int) '\b', encoding); // Backspace
    encodings.put(       0xb, encoding); // Vertical Tab
    encodings.put(       0xe, encoding); // Shift Out
    encodings.put(       0xf, encoding); // Shift In
    encodings.put(      0x10, encoding); // Data Link Escape
    encodings.put(      0x11, encoding); // Device Control 1 (oft. XON)
    encodings.put(      0x12, encoding); // Device Control 2
    encodings.put(      0x13, encoding); // Device Control 3 (oft. XOFF)
    encodings.put(      0x14, encoding); // Device Control 4
    encodings.put(      0x15, encoding); // Negative Acknowledgment
    encodings.put(      0x16, encoding); // Synchronous idle
    encodings.put(      0x17, encoding); // End of Transmission Block
    encodings.put(      0x18, encoding); // Cancel
    encodings.put(      0x19, encoding); // End of Medium
    encodings.put(      0x1a, encoding); // Substitute
    encodings.put(      0x1b, encoding); // Escape
    encodings.put(      0x1c, encoding); // File Separator
    encodings.put(      0x1d, encoding); // Group Separator
    encodings.put(      0x1e, encoding); // Record Separator
    encodings.put(      0x1f, encoding); // Unit Separator
    encodings.put(      0x7f, encoding); // Delete
    return this;
  }

  public UrlComponentEncodingTester nonAscii(Encoding encoding) {
    encodings.put(UNICODE_2, encoding);
    encodings.put(UNICODE_3, encoding);
    encodings.put(UNICODE_4, encoding);
    return this;
  }

  /**
   * Configure code points to be escaped for conversion to {@code java.net.URI}. That class is more
   * strict than the others.
   */
  public UrlComponentEncodingTester escapeForUri(int... codePoints) {
    uriEscapedCodePoints.append(new String(codePoints, 0, codePoints.length));
    return this;
  }

  /**
   * Configure code points to be stripped in conversion to {@code java.net.URI}. That class is more
   * strict than the others.
   */
  public UrlComponentEncodingTester stripForUri(int... codePoints) {
    uriStrippedCodePoints.append(new String(codePoints, 0, codePoints.length));
    return this;
  }

  public UrlComponentEncodingTester test(Component component) {
    for (Map.Entry<Integer, Encoding> entry : encodings.entrySet()) {
      Encoding encoding = entry.getValue();
      int codePoint = entry.getKey();
      String codePointString = Encoding.IDENTITY.encode(codePoint);

      if (encoding == Encoding.FORBIDDEN) {
        testForbidden(codePoint, codePointString, component);
        continue;
      }

      testEncodeAndDecode(codePoint, codePointString, component);
      if (encoding == Encoding.SKIP) continue;

      testParseOriginal(codePoint, codePointString, encoding, component);
      testParseAlreadyEncoded(codePoint, encoding, component);
      testToUrl(codePoint, encoding, component);
      testFromUrl(codePoint, encoding, component);
      testUri(codePoint, codePointString, encoding, component);
    }
    return this;
  }

  private void testParseAlreadyEncoded(int codePoint, Encoding encoding, Component component) {
    String expected = component.canonicalize(encoding.encode(codePoint));
    String urlString = component.urlString(expected);
    HttpUrl url = HttpUrl.get(urlString);
    String actual = component.encodedValue(url);
    if (!actual.equals(expected)) {
      fail(Util.format("Encoding %s %#x using %s: '%s' != '%s'",
          component, codePoint, encoding, actual, expected));
    }
  }

  private void testEncodeAndDecode(int codePoint, String codePointString, Component component) {
    HttpUrl.Builder builder = HttpUrl.get("http://host/").newBuilder();
    component.set(builder, codePointString);
    HttpUrl url = builder.build();
    String expected = component.canonicalize(codePointString);
    String actual = component.get(url);
    if (!expected.equals(actual)) {
      fail(Util.format("Roundtrip %s %#x %s", component, codePoint, url));
    }
  }

  private void testParseOriginal(
      int codePoint, String codePointString, Encoding encoding, Component component) {
    String expected = encoding.encode(codePoint);
    if (encoding != Encoding.PERCENT) return;
    String urlString = component.urlString(codePointString);
    HttpUrl url = HttpUrl.get(urlString);

    String actual = component.encodedValue(url);
    if (!actual.equals(expected)) {
      fail(Util.format("Encoding %s %#02x using %s: '%s' != '%s'",
          component, codePoint, encoding, actual, expected));
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
      int codePoint, String codePointString, Encoding encoding, Component component) {
    if (codePoint == '%') return;

    String encoded = encoding.encode(codePoint);
    HttpUrl httpUrl = HttpUrl.get(component.urlString(encoded));
    URI uri = httpUrl.uri();
    HttpUrl toAndFromUri = HttpUrl.get(uri);

    boolean uriStripped = uriStrippedCodePoints.indexOf(codePointString) != -1;
    if (uriStripped) {
      if (!uri.toString().equals(component.urlString(""))) {
        fail(Util.format("Encoding %s %#x using %s", component, codePoint, encoding));
      }
      return;
    }

    // If the URI has more escaping than the HttpURL, check that the decoded values still match.
    boolean uriEscaped = uriEscapedCodePoints.indexOf(codePointString) != -1;
    if (uriEscaped) {
      if (uri.toString().equals(httpUrl.toString())) {
        fail(Util.format("Encoding %s %#x using %s", component, codePoint, encoding));
      }
      if (!component.get(toAndFromUri).equals(codePointString)) {
        fail(Util.format("Encoding %s %#x using %s", component, codePoint, encoding));
      }
      return;
    }

    // Check that the URI and HttpURL have the exact same escaping.
    if (!toAndFromUri.equals(httpUrl)) {
      fail(Util.format("Encoding %s %#x using %s", component, codePoint, encoding));
    }
    if (!uri.toString().equals(httpUrl.toString())) {
      fail(Util.format("Encoding %s %#x using %s", component, codePoint, encoding));
    }
  }

  private void testForbidden(int codePoint, String codePointString, Component component) {
    HttpUrl.Builder builder = HttpUrl.get("http://host/").newBuilder();
    try {
      component.set(builder, codePointString);
      fail(Util.format("Accepted forbidden code point %s %#x", component, codePoint));
    } catch (IllegalArgumentException expected) {
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

    /** URLs that contain this character in this component are invalid. */
    FORBIDDEN,

    /** This code point is special and should not be tested. */
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
    HOST {
      @Override public String urlString(String value) {
        return "http://a" + value + "z.com/";
      }

      @Override public String encodedValue(HttpUrl url) {
        return get(url);
      }

      @Override public void set(HttpUrl.Builder builder, String value) {
        builder.host("a" + value + "z.com");
      }

      @Override public String get(HttpUrl url) {
        String host = url.host();
        return host.substring(1, host.length() - 5).toLowerCase(Locale.ROOT);
      }

      @Override public String canonicalize(String s) {
        return s.toLowerCase(Locale.US);
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

    /**
     * Returns a character equivalent to 's' in this component. This is used to convert hostname
     * characters to lowercase.
     */
    public String canonicalize(String s) {
      return s;
    }
  }
}
