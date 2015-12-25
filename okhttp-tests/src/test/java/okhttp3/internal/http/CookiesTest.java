/*
 * Copyright (C) 2010 The Android Open Source Project
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

package okhttp3.internal.http;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.OkUrlFactory;
import okhttp3.internal.JavaNetCookieJar;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Before;
import org.junit.Test;

import static java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Android's CookiesTest. */
public class CookiesTest {

  private OkHttpClient client;

  @Before
  public void setUp() throws Exception {
    client = new OkHttpClient();
  }

  @Test
  public void testNetscapeResponse() throws Exception {
    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    client.setCookieJar(new JavaNetCookieJar(cookieManager));
    MockWebServer server = new MockWebServer();
    server.start();

    server.enqueue(new MockResponse().addHeader("Set-Cookie: a=android; "
        + "expires=Fri, 31-Dec-9999 23:59:59 GMT; "
        + "path=/path; "
        + "domain=" + server.getCookieDomain() + "; "
        + "secure"));
    get(server, "/path/foo");

    List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
    assertEquals(1, cookies.size());
    HttpCookie cookie = cookies.get(0);
    assertEquals("a", cookie.getName());
    assertEquals("android", cookie.getValue());
    assertEquals(null, cookie.getComment());
    assertEquals(null, cookie.getCommentURL());
    assertEquals(false, cookie.getDiscard());
    assertTrue(server.getCookieDomain().equalsIgnoreCase(cookie.getDomain()));
    assertTrue(cookie.getMaxAge() > 100000000000L);
    assertEquals("/path", cookie.getPath());
    assertEquals(true, cookie.getSecure());
    assertEquals(0, cookie.getVersion());
  }

  @Test public void testRfc2109Response() throws Exception {
    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    client.setCookieJar(new JavaNetCookieJar(cookieManager));
    MockWebServer server = new MockWebServer();
    server.start();

    server.enqueue(new MockResponse().addHeader("Set-Cookie: a=android; "
        + "Comment=this cookie is delicious; "
        + "Domain=" + server.getCookieDomain() + "; "
        + "Max-Age=60; "
        + "Path=/path; "
        + "Secure; "
        + "Version=1"));
    get(server, "/path/foo");

    List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
    assertEquals(1, cookies.size());
    HttpCookie cookie = cookies.get(0);
    assertEquals("a", cookie.getName());
    assertEquals("android", cookie.getValue());
    assertEquals("this cookie is delicious", cookie.getComment());
    assertEquals(null, cookie.getCommentURL());
    assertEquals(false, cookie.getDiscard());
    assertTrue(server.getCookieDomain().equalsIgnoreCase(cookie.getDomain()));
    assertEquals(60, cookie.getMaxAge());
    assertEquals("/path", cookie.getPath());
    assertEquals(true, cookie.getSecure());
    assertEquals(1, cookie.getVersion());
  }

  @Test public void testRfc2965Response() throws Exception {
    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    client.setCookieJar(new JavaNetCookieJar(cookieManager));
    MockWebServer server = new MockWebServer();
    server.start();

    server.enqueue(new MockResponse().addHeader("Set-Cookie2: a=android; "
        + "Comment=this cookie is delicious; "
        + "CommentURL=http://google.com/; "
        + "Discard; "
        + "Domain=" + server.getCookieDomain() + "; "
        + "Max-Age=60; "
        + "Path=/path; "
        + "Port=\"80,443," + server.getPort() + "\"; "
        + "Secure; "
        + "Version=1"));
    get(server, "/path/foo");

    List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
    assertEquals(1, cookies.size());
    HttpCookie cookie = cookies.get(0);
    assertEquals("a", cookie.getName());
    assertEquals("android", cookie.getValue());
    assertEquals("this cookie is delicious", cookie.getComment());
    assertEquals("http://google.com/", cookie.getCommentURL());
    assertEquals(true, cookie.getDiscard());
    assertTrue(server.getCookieDomain().equalsIgnoreCase(cookie.getDomain()));
    assertEquals(60, cookie.getMaxAge());
    assertEquals("/path", cookie.getPath());
    assertEquals("80,443," + server.getPort(), cookie.getPortlist());
    assertEquals(true, cookie.getSecure());
    assertEquals(1, cookie.getVersion());
  }

  @Test public void testQuotedAttributeValues() throws Exception {
    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    client.setCookieJar(new JavaNetCookieJar(cookieManager));
    MockWebServer server = new MockWebServer();
    server.start();

    server.enqueue(new MockResponse().addHeader("Set-Cookie2: a=\"android\"; "
        + "Comment=\"this cookie is delicious\"; "
        + "CommentURL=\"http://google.com/\"; "
        + "Discard; "
        + "Domain=\"" + server.getCookieDomain() + "\"; "
        + "Max-Age=\"60\"; "
        + "Path=\"/path\"; "
        + "Port=\"80,443," + server.getPort() + "\"; "
        + "Secure; "
        + "Version=\"1\""));
    get(server, "/path/foo");

    List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
    assertEquals(1, cookies.size());
    HttpCookie cookie = cookies.get(0);
    assertEquals("a", cookie.getName());
    assertEquals("android", cookie.getValue());
    assertEquals("this cookie is delicious", cookie.getComment());
    assertEquals("http://google.com/", cookie.getCommentURL());
    assertEquals(true, cookie.getDiscard());
    assertTrue(server.getCookieDomain().equalsIgnoreCase(cookie.getDomain()));
    assertEquals(60, cookie.getMaxAge());
    assertEquals("/path", cookie.getPath());
    assertEquals("80,443," + server.getPort(), cookie.getPortlist());
    assertEquals(true, cookie.getSecure());
    assertEquals(1, cookie.getVersion());
  }

  @Test public void testSendingCookiesFromStore() throws Exception {
    MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse());
    server.start();

    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    HttpCookie cookieA = new HttpCookie("a", "android");
    cookieA.setDomain(server.getCookieDomain());
    cookieA.setPath("/");
    cookieManager.getCookieStore().add(server.url("/").uri(), cookieA);
    HttpCookie cookieB = new HttpCookie("b", "banana");
    cookieB.setDomain(server.getCookieDomain());
    cookieB.setPath("/");
    cookieManager.getCookieStore().add(server.url("/").uri(), cookieB);
    client.setCookieJar(new JavaNetCookieJar(cookieManager));

    get(server, "/");
    RecordedRequest request = server.takeRequest();

    assertEquals("$Version=\"1\"; "
        + "a=\"android\";$Path=\"/\";$Domain=\""
        + server.getCookieDomain()
        + "\"; "
        + "b=\"banana\";$Path=\"/\";$Domain=\""
        + server.getCookieDomain()
        + "\"", request.getHeader("Cookie"));
  }

  @Test public void testRedirectsDoNotIncludeTooManyCookies() throws Exception {
    MockWebServer redirectTarget = new MockWebServer();
    redirectTarget.enqueue(new MockResponse().setBody("A"));
    redirectTarget.start();

    MockWebServer redirectSource = new MockWebServer();
    redirectSource.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + redirectTarget.url("/")));
    redirectSource.start();

    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    HttpCookie cookie = new HttpCookie("c", "cookie");
    cookie.setDomain(redirectSource.getCookieDomain());
    cookie.setPath("/");
    String portList = Integer.toString(redirectSource.getPort());
    cookie.setPortlist(portList);
    cookieManager.getCookieStore().add(redirectSource.url("/").uri(), cookie);
    client.setCookieJar(new JavaNetCookieJar(cookieManager));

    get(redirectSource, "/");
    RecordedRequest request = redirectSource.takeRequest();

    assertEquals("$Version=\"1\"; "
        + "c=\"cookie\";$Path=\"/\";$Domain=\""
        + redirectSource.getCookieDomain()
        + "\";$Port=\""
        + portList
        + "\"", request.getHeader("Cookie"));

    for (String header : redirectTarget.takeRequest().getHeaders().names()) {
      if (header.startsWith("Cookie")) {
        fail(header);
      }
    }
  }

  /**
   * Test which headers show up where. The cookie manager should be notified of both user-specified
   * and derived headers like {@code Host}. All headers returned from the cookie jar are retained
   * including {@code Cookie}, {@code Cookie2}, and any other headers.
   *
   * <p>This behavior is different from {@link JavaNetCookieJar}, which retains only headers named
   * {@code Cookie} and {@code Cookie2}.
   */
  @Test public void testHeadersSentToCookieHandler() throws IOException, InterruptedException {
    final AtomicReference<Headers> requestHeadersRef = new AtomicReference<>();
    client.setCookieJar(new CookieJar() {
      @Override public void saveFromResponse(HttpUrl url, Headers headers) {
      }

      @Override public Headers loadForRequest(HttpUrl url, Headers headers) {
        requestHeadersRef.set(headers);
        Headers.Builder result = headers.newBuilder();
        result.add("Cookie", "Bar=bar");
        result.add("Cookie2", "Baz=baz");
        result.add("Quux", "quux");
        return result.build();
      }
    });
    MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse());
    server.start();

    HttpURLConnection connection = new OkUrlFactory(client).open(server.url("/").url());
    assertEquals(Collections.<String, List<String>>emptyMap(),
        connection.getRequestProperties());

    connection.setRequestProperty("Foo", "foo");
    connection.setDoOutput(true);
    connection.getOutputStream().write(5);
    connection.getOutputStream().close();
    connection.getInputStream().close();

    RecordedRequest request = server.takeRequest();

    Headers requestHeaders = requestHeadersRef.get();
    assertEquals(Arrays.asList("foo"), requestHeaders.values("Foo"));
    assertNotNull(requestHeadersRef.get().get("Content-Type"));
    assertNotNull(requestHeadersRef.get().get("User-Agent"));
    assertNotNull(requestHeadersRef.get().get("Connection"));
    assertNotNull(requestHeadersRef.get().get("Host"));
    assertNull(requestHeadersRef.get().get("Cookie"));

    /*
     * The API specifies that calling getRequestProperties() on a connected instance should fail
     * with an IllegalStateException, but the RI violates the spec and returns a valid map.
     * http://www.mail-archive.com/net-dev@openjdk.java.net/msg01768.html
     */
    try {
      assertContainsAll(connection.getRequestProperties().keySet(), "Foo");
      assertContainsAll(connection.getRequestProperties().keySet(),
          "Content-type", "Content-Length", "User-Agent", "Connection", "Host");
      assertContainsAll(connection.getRequestProperties().keySet(), "Cookie", "Cookie2");
      assertFalse(connection.getRequestProperties().containsKey("Quux"));
    } catch (IllegalStateException expected) {
    }

    assertEquals("foo", request.getHeader("Foo"));
    assertEquals("Bar=bar", request.getHeader("Cookie"));
    assertEquals("Baz=baz", request.getHeader("Cookie2"));
    assertEquals("quux", request.getHeader("Quux"));
  }

  @Test public void testCookiesSentIgnoresCase() throws Exception {
    client.setCookieJar(new CookieJar() {
      @Override public void saveFromResponse(HttpUrl url, Headers headers) {
      }

      @Override public Headers loadForRequest(HttpUrl url, Headers headers) {
        return headers.newBuilder()
            .add("COOKIE", "Bar=bar")
            .add("cooKIE2", "Baz=baz")
            .build();
      }
    });
    MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse());
    server.start();

    get(server, "/");

    RecordedRequest request = server.takeRequest();
    assertEquals("Bar=bar", request.getHeader("Cookie"));
    assertEquals("Baz=baz", request.getHeader("Cookie2"));
    assertNull(request.getHeader("Quux"));
  }

  private void assertContains(Collection<String> collection, String element) {
    for (String c : collection) {
      if (c != null && c.equalsIgnoreCase(element)) {
        return;
      }
    }
    fail("No " + element + " in " + collection);
  }

  private void assertContainsAll(Collection<String> collection, String... toFind) {
    for (String s : toFind) {
      assertContains(collection, s);
    }
  }

  private Map<String, List<String>> get(MockWebServer server, String path) throws Exception {
    URLConnection connection = new OkUrlFactory(client).open(server.url(path).url());
    Map<String, List<String>> headers = connection.getHeaderFields();
    connection.getInputStream().close();
    return headers;
  }
}
