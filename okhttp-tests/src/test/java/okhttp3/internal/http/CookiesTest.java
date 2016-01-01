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
import java.net.InetAddress;
import java.net.URI;
import java.net.URLConnection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.OkUrlFactory;
import okhttp3.internal.JavaNetCookieJar;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;

import static java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER;
import static okhttp3.TestUtil.defaultClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Android's CookiesTest. */
public class CookiesTest {
  private OkHttpClient client = defaultClient();

  @Test
  public void testNetscapeResponse() throws Exception {
    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    client = client.newBuilder()
        .setCookieJar(new JavaNetCookieJar(cookieManager))
        .build();
    MockWebServer server = new MockWebServer();
    server.start();

    HttpUrl urlWithIpAddress = urlWithIpAddress(server, "/path/foo");
    server.enqueue(new MockResponse().addHeader("Set-Cookie: a=android; "
        + "expires=Fri, 31-Dec-9999 23:59:59 GMT; "
        + "path=/path; "
        + "domain=" + urlWithIpAddress.host() + "; "
        + "secure"));
    get(urlWithIpAddress);

    List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
    assertEquals(1, cookies.size());
    HttpCookie cookie = cookies.get(0);
    assertEquals("a", cookie.getName());
    assertEquals("android", cookie.getValue());
    assertEquals(null, cookie.getComment());
    assertEquals(null, cookie.getCommentURL());
    assertEquals(false, cookie.getDiscard());
    assertTrue(cookie.getMaxAge() > 100000000000L);
    assertEquals("/path", cookie.getPath());
    assertEquals(true, cookie.getSecure());
    assertEquals(0, cookie.getVersion());
  }

  @Test public void testRfc2109Response() throws Exception {
    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    client = client.newBuilder()
        .setCookieJar(new JavaNetCookieJar(cookieManager))
        .build();
    MockWebServer server = new MockWebServer();
    server.start();

    HttpUrl urlWithIpAddress = urlWithIpAddress(server, "/path/foo");
    server.enqueue(new MockResponse().addHeader("Set-Cookie: a=android; "
        + "Comment=this cookie is delicious; "
        + "Domain=" + urlWithIpAddress.host() + "; "
        + "Max-Age=60; "
        + "Path=/path; "
        + "Secure; "
        + "Version=1"));
    get(urlWithIpAddress);

    List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
    assertEquals(1, cookies.size());
    HttpCookie cookie = cookies.get(0);
    assertEquals("a", cookie.getName());
    assertEquals("android", cookie.getValue());
    assertEquals(null, cookie.getCommentURL());
    assertEquals(false, cookie.getDiscard());
    assertEquals(60.0, cookie.getMaxAge(), 1.0); // Converting to a fixed date can cause rounding!
    assertEquals("/path", cookie.getPath());
    assertEquals(true, cookie.getSecure());
  }

  @Test public void testQuotedAttributeValues() throws Exception {
    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    client = client.newBuilder()
        .setCookieJar(new JavaNetCookieJar(cookieManager))
        .build();
    MockWebServer server = new MockWebServer();
    server.start();

    HttpUrl urlWithIpAddress = urlWithIpAddress(server, "/path/foo");
    server.enqueue(new MockResponse().addHeader("Set-Cookie: a=\"android\"; "
        + "Comment=\"this cookie is delicious\"; "
        + "CommentURL=\"http://google.com/\"; "
        + "Discard; "
        + "Domain=" + urlWithIpAddress.host() + "; "
        + "Max-Age=60; "
        + "Path=\"/path\"; "
        + "Port=\"80,443," + server.getPort() + "\"; "
        + "Secure; "
        + "Version=\"1\""));
    get(urlWithIpAddress);

    List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
    assertEquals(1, cookies.size());
    HttpCookie cookie = cookies.get(0);
    assertEquals("a", cookie.getName());
    assertEquals("android", cookie.getValue());
    assertEquals(60.0, cookie.getMaxAge(), 1.0); // Converting to a fixed date can cause rounding!
    assertEquals("/path", cookie.getPath());
    assertEquals(true, cookie.getSecure());
  }

  @Test public void testSendingCookiesFromStore() throws Exception {
    MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse());
    server.start();

    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    HttpCookie cookieA = new HttpCookie("a", "android");
    cookieA.setDomain(server.getHostName());
    cookieA.setPath("/");
    cookieManager.getCookieStore().add(server.url("/").uri(), cookieA);
    HttpCookie cookieB = new HttpCookie("b", "banana");
    cookieB.setDomain(server.getHostName());
    cookieB.setPath("/");
    cookieManager.getCookieStore().add(server.url("/").uri(), cookieB);
    client = client.newBuilder()
        .setCookieJar(new JavaNetCookieJar(cookieManager))
        .build();

    get(server.url("/"));
    RecordedRequest request = server.takeRequest();

    assertEquals("a=android; b=banana", request.getHeader("Cookie"));
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
    cookie.setDomain(redirectSource.getHostName());
    cookie.setPath("/");
    String portList = Integer.toString(redirectSource.getPort());
    cookie.setPortlist(portList);
    cookieManager.getCookieStore().add(redirectSource.url("/").uri(), cookie);
    client = client.newBuilder()
        .setCookieJar(new JavaNetCookieJar(cookieManager))
        .build();

    get(redirectSource.url("/"));
    RecordedRequest request = redirectSource.takeRequest();

    assertEquals("c=cookie", request.getHeader("Cookie"));

    for (String header : redirectTarget.takeRequest().getHeaders().names()) {
      if (header.startsWith("Cookie")) {
        fail(header);
      }
    }
  }

  @Test public void testCookiesSentIgnoresCase() throws Exception {
    client = client.newBuilder()
        .setCookieJar(new JavaNetCookieJar(new CookieManager() {
          @Override public Map<String, List<String>> get(URI uri,
              Map<String, List<String>> requestHeaders) throws IOException {
            Map<String, List<String>> result = new HashMap<>();
            result.put("COOKIE", Collections.singletonList("Bar=bar"));
            result.put("cooKIE2", Collections.singletonList("Baz=baz"));
            return result;
          }
        }))
        .build();

    MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse());
    server.start();

    get(server.url("/"));

    RecordedRequest request = server.takeRequest();
    assertEquals("Bar=bar; Baz=baz", request.getHeader("Cookie"));
    assertNull(request.getHeader("Cookie2"));
    assertNull(request.getHeader("Quux"));
  }

  private HttpUrl urlWithIpAddress(MockWebServer server, String path) throws Exception {
    return server.url(path)
        .newBuilder()
        .host(InetAddress.getByName(server.getHostName()).getHostAddress())
        .build();
  }

  private Map<String, List<String>> get(HttpUrl url) throws Exception {
    URLConnection connection = new OkUrlFactory(client).open(url.url());
    Map<String, List<String>> headers = connection.getHeaderFields();
    connection.getInputStream().close();
    return headers;
  }
}
