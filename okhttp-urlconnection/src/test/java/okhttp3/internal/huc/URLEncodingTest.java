/*
 * Copyright (C) 2009 The Android Open Source Project
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

package okhttp3.internal.huc;

import okhttp3.OkHttpClient;
import okhttp3.OkUrlFactory;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.InternalCache;
import okhttp3.internal.http.CacheRequest;
import okhttp3.internal.http.CacheStrategy;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Exercises HttpURLConnection to convert URL to a URI. Unlike URL#toURI,
 * HttpURLConnection recovers from URLs with unescaped but unsupported URI
 * characters like '{' and '|' by escaping these characters.
 */
public final class URLEncodingTest {
  /**
   * This test goes through the exhaustive set of interesting ASCII characters
   * because most of those characters are interesting in some way according to
   * RFC 2396 and RFC 2732. http://b/1158780
   */
  @Test @Ignore public void lenientUrlToUri() throws Exception {
    // alphanum
    testUrlToUriMapping("abzABZ09", "abzABZ09", "abzABZ09", "abzABZ09", "abzABZ09");

    // control characters
    testUrlToUriMapping("\u0001", "%01", "%01", "%01", "%01");
    testUrlToUriMapping("\u001f", "%1F", "%1F", "%1F", "%1F");

    // ascii characters
    testUrlToUriMapping("%20", "%20", "%20", "%20", "%20");
    testUrlToUriMapping("%20", "%20", "%20", "%20", "%20");
    testUrlToUriMapping(" ", "%20", "%20", "%20", "%20");
    testUrlToUriMapping("!", "!", "!", "!", "!");
    testUrlToUriMapping("\"", "%22", "%22", "%22", "%22");
    testUrlToUriMapping("#", null, null, null, "%23");
    testUrlToUriMapping("$", "$", "$", "$", "$");
    testUrlToUriMapping("&", "&", "&", "&", "&");
    testUrlToUriMapping("'", "'", "'", "'", "'");
    testUrlToUriMapping("(", "(", "(", "(", "(");
    testUrlToUriMapping(")", ")", ")", ")", ")");
    testUrlToUriMapping("*", "*", "*", "*", "*");
    testUrlToUriMapping("+", "+", "+", "+", "+");
    testUrlToUriMapping(",", ",", ",", ",", ",");
    testUrlToUriMapping("-", "-", "-", "-", "-");
    testUrlToUriMapping(".", ".", ".", ".", ".");
    testUrlToUriMapping("/", null, "/", "/", "/");
    testUrlToUriMapping(":", null, ":", ":", ":");
    testUrlToUriMapping(";", ";", ";", ";", ";");
    testUrlToUriMapping("<", "%3C", "%3C", "%3C", "%3C");
    testUrlToUriMapping("=", "=", "=", "=", "=");
    testUrlToUriMapping(">", "%3E", "%3E", "%3E", "%3E");
    testUrlToUriMapping("?", null, null, "?", "?");
    testUrlToUriMapping("@", "@", "@", "@", "@");
    testUrlToUriMapping("[", null, "%5B", null, "%5B");
    testUrlToUriMapping("\\", "%5C", "%5C", "%5C", "%5C");
    testUrlToUriMapping("]", null, "%5D", null, "%5D");
    testUrlToUriMapping("^", "%5E", "%5E", "%5E", "%5E");
    testUrlToUriMapping("_", "_", "_", "_", "_");
    testUrlToUriMapping("`", "%60", "%60", "%60", "%60");
    testUrlToUriMapping("{", "%7B", "%7B", "%7B", "%7B");
    testUrlToUriMapping("|", "%7C", "%7C", "%7C", "%7C");
    testUrlToUriMapping("}", "%7D", "%7D", "%7D", "%7D");
    testUrlToUriMapping("~", "~", "~", "~", "~");
    testUrlToUriMapping("~", "~", "~", "~", "~");
    testUrlToUriMapping("\u007f", "%7F", "%7F", "%7F", "%7F");

    // beyond ascii
    testUrlToUriMapping("\u0080", "%C2%80", "%C2%80", "%C2%80", "%C2%80");
    testUrlToUriMapping("\u20ac", "\u20ac", "\u20ac", "\u20ac", "\u20ac");
    testUrlToUriMapping("\ud842\udf9f", "\ud842\udf9f", "\ud842\udf9f", "\ud842\udf9f",
        "\ud842\udf9f");
  }

  @Test @Ignore public void lenientUrlToUriNul() throws Exception {
    testUrlToUriMapping("\u0000", "%00", "%00", "%00", "%00"); // RI fails this
  }

  private void testUrlToUriMapping(String string, String asAuthority, String asFile, String asQuery,
      String asFragment) throws Exception {
    if (asAuthority != null) {
      assertEquals("http://host" + asAuthority + ".tld/",
          backdoorUrlToUri(new URL("http://host" + string + ".tld/")).toString());
    }
    if (asFile != null) {
      assertEquals("http://host.tld/file" + asFile + "/",
          backdoorUrlToUri(new URL("http://host.tld/file" + string + "/")).toString());
    }
    if (asQuery != null) {
      assertEquals("http://host.tld/file?q" + asQuery + "=x",
          backdoorUrlToUri(new URL("http://host.tld/file?q" + string + "=x")).toString());
    }
    assertEquals("http://host.tld/file#" + asFragment + "-x",
        backdoorUrlToUri(new URL("http://host.tld/file#" + asFragment + "-x")).toString());
  }

  private URI backdoorUrlToUri(URL url) throws Exception {
    final AtomicReference<URI> uriReference = new AtomicReference<>();

    OkHttpClient client = new OkHttpClient();
    Internal.instance.setCache(client, new InternalCache() {
      @Override
      public Response get(Request request) throws IOException {
        uriReference.set(request.url().uri());
        throw new UnsupportedOperationException();
      }

      @Override
      public CacheRequest put(Response response) throws IOException {
        return null;
      }

      @Override
      public void remove(Request request) throws IOException {

      }

      @Override
      public void update(Response cached, Response network) throws IOException {

      }

      @Override
      public void trackConditionalCacheHit() {

      }

      @Override
      public void trackResponse(CacheStrategy cacheStrategy) {

      }
    });

    try {
      HttpURLConnection connection = new OkUrlFactory(client).open(url);
      connection.getResponseCode();
    } catch (Exception expected) {
      if (expected.getCause() instanceof URISyntaxException) {
        expected.printStackTrace();
      }
    }

    return uriReference.get();
  }
}
