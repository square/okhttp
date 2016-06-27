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

package okhttp3;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/** Derived from Android's CookiesTest. */
public class CookiesTest {
  private OkHttpClient client = defaultClient();

  @Test
  public void testNetscapeResponse() throws Exception {
    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    client = client.newBuilder()
        .cookieJar(new JavaNetCookieJar(cookieManager))
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
        .cookieJar(new JavaNetCookieJar(cookieManager))
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
        .cookieJar(new JavaNetCookieJar(cookieManager))
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
    HttpUrl serverUrl = urlWithIpAddress(server, "/");

    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    HttpCookie cookieA = new HttpCookie("a", "android");
    cookieA.setDomain(serverUrl.host());
    cookieA.setPath("/");
    cookieManager.getCookieStore().add(serverUrl.uri(), cookieA);
    HttpCookie cookieB = new HttpCookie("b", "banana");
    cookieB.setDomain(serverUrl.host());
    cookieB.setPath("/");
    cookieManager.getCookieStore().add(serverUrl.uri(), cookieB);
    client = client.newBuilder()
        .cookieJar(new JavaNetCookieJar(cookieManager))
        .build();

    get(serverUrl);
    RecordedRequest request = server.takeRequest();

    assertEquals("a=android; b=banana", request.getHeader("Cookie"));
  }

  @Test public void cookieHandlerLikeAndroid() throws Exception {
    final MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse());
    server.start();
    final HttpUrl serverUrl = urlWithIpAddress(server, "/");

    CookieHandler androidCookieHandler = new CookieHandler() {
      @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> map)
          throws IOException {
        return Collections.singletonMap("Cookie", Collections.singletonList("$Version=\"1\"; "
            + "a=\"android\";$Path=\"/\";$Domain=\"" + serverUrl.host() + "\"; "
            + "b=\"banana\";$Path=\"/\";$Domain=\"" + serverUrl.host() + "\""));
      }

      @Override public void put(URI uri, Map<String, List<String>> map) throws IOException {
      }
    };

    client = client.newBuilder()
        .cookieJar(new JavaNetCookieJar(androidCookieHandler))
        .build();

    get(serverUrl);
    RecordedRequest request = server.takeRequest();

    assertEquals("a=android; b=banana", request.getHeader("Cookie"));
  }

  @Test public void receiveAndSendMultipleCookies() throws Exception {
    MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse()
        .addHeader("Set-Cookie", "a=android")
        .addHeader("Set-Cookie", "b=banana"));
    server.enqueue(new MockResponse());
    server.start();

    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    client = client.newBuilder()
        .cookieJar(new JavaNetCookieJar(cookieManager))
        .build();

    get(urlWithIpAddress(server, "/"));
    RecordedRequest request1 = server.takeRequest();
    assertNull(request1.getHeader("Cookie"));

    get(urlWithIpAddress(server, "/"));
    RecordedRequest request2 = server.takeRequest();
    assertEquals("a=android; b=banana", request2.getHeader("Cookie"));
  }

  @Test public void testRedirectsDoNotIncludeTooManyCookies() throws Exception {
    MockWebServer redirectTarget = new MockWebServer();
    redirectTarget.enqueue(new MockResponse().setBody("A"));
    redirectTarget.start();
    HttpUrl redirectTargetUrl = urlWithIpAddress(redirectTarget, "/");

    MockWebServer redirectSource = new MockWebServer();
    redirectSource.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + redirectTargetUrl));
    redirectSource.start();
    HttpUrl redirectSourceUrl = urlWithIpAddress(redirectSource, "/");

    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    HttpCookie cookie = new HttpCookie("c", "cookie");
    cookie.setDomain(redirectSourceUrl.host());
    cookie.setPath("/");
    String portList = Integer.toString(redirectSource.getPort());
    cookie.setPortlist(portList);
    cookieManager.getCookieStore().add(redirectSourceUrl.uri(), cookie);
    client = client.newBuilder()
        .cookieJar(new JavaNetCookieJar(cookieManager))
        .build();

    get(redirectSourceUrl);
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
        .cookieJar(new JavaNetCookieJar(new CookieManager() {
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

  private void get(HttpUrl url) throws Exception {
    Call call = client.newCall(new Request.Builder()
        .url(url)
        .build());
    Response response = call.execute();
    response.body().close();
  }
}
