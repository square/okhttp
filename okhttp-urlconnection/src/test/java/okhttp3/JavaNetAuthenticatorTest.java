/*
 * Copyright (C) 2018 Square, Inc.
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

import org.junit.After;
import org.junit.Test;

import javax.net.SocketFactory;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.net.InetSocketAddress.createUnresolved;
import static java.net.Proxy.Type.HTTP;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static okhttp3.Protocol.HTTP_1_1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class JavaNetAuthenticatorTest {
  private static JavaNetAuthenticator javaNetAuthenticator = new JavaNetAuthenticator();

  private List<String> challenges;
  private String realmToAnswer;
  private boolean proxyAuthenticate;

  private int responseCode;
  private String authenticateHeaderName;
  private String authorizationHeaderName;
  private Proxy proxy = new Proxy(HTTP, createUnresolved("localhost", 80));
  private Route route = new Route(
      new Address("localhost",
          80,
          Dns.SYSTEM,
          SocketFactory.getDefault(),
          null,
          null,
          null,
          Authenticator.NONE,
          null,
          Collections.<Protocol>emptyList(),
          Collections.<ConnectionSpec>emptyList(),
          ProxySelector.getDefault()),
      proxy,
      createUnresolved("localhost", 80));

  private void setup() {
    if (proxyAuthenticate) {
      responseCode = HTTP_PROXY_AUTH;
      authenticateHeaderName = "Proxy-Authenticate";
      authorizationHeaderName = "Proxy-Authorization";
    } else {
      responseCode = HTTP_UNAUTHORIZED;
      authenticateHeaderName = "WWW-Authenticate";
      authorizationHeaderName = "Authorization";
    }

    java.net.Authenticator.setDefault(new java.net.Authenticator() {
      @Override protected PasswordAuthentication getPasswordAuthentication() {
        if (realmToAnswer.equals(getRequestingPrompt())) {
          return new PasswordAuthentication("username", "password".toCharArray());
        }
        return null;
      }
    });
  }

  @After public void tearDown() {
    java.net.Authenticator.setDefault(null);
  }

  private Response buildResponse(Request request) {
    Response.Builder responseBuilder = new Response.Builder()
        .request(request)
        .protocol(HTTP_1_1)
        .code(responseCode)
        .message("");
    for (String challenge : challenges) {
      responseBuilder.addHeader(authenticateHeaderName, challenge);
    }
    return responseBuilder.build();
  }

  private void correctAuthenticationHeaderIsSet() throws UnknownHostException {
    setup();
    Response response = buildResponse(new Request.Builder().url("http://localhost").build());
    Request returnedRequest = javaNetAuthenticator.authenticate(route, response);

    assertNotNull(returnedRequest);
    assertEquals("Basic dXNlcm5hbWU6cGFzc3dvcmQ=", returnedRequest.header(authorizationHeaderName));
  }

  private void credentialsAreNotRetriedTwiceInARow() throws UnknownHostException {
    setup();
    Request request = new Request.Builder()
        .url("http://localhost")
        .header(authorizationHeaderName, "Basic dXNlcm5hbWU6cGFzc3dvcmQ=")
        .build();
    Response response = buildResponse(request);

    assertNull(javaNetAuthenticator.authenticate(route, response));
  }

  private void newCredentialsAreTriedIfOthersDidNotWork() throws UnknownHostException {
    setup();
    Request request = new Request.Builder()
        .url("http://localhost")
        .header(authorizationHeaderName, "wrong credentials")
        .build();
    Response response = buildResponse(request);
    Request returnedRequest = javaNetAuthenticator.authenticate(route, response);

    assertNotNull(returnedRequest);
    assertEquals("Basic dXNlcm5hbWU6cGFzc3dvcmQ=", returnedRequest.header(authorizationHeaderName));
  }

  @Test public void correctAuthenticationHeaderIsSet1() throws UnknownHostException {
    challenges = singletonList(" ,  , Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet2() throws UnknownHostException {
    challenges = singletonList("Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet3() throws UnknownHostException {
    challenges = singletonList("Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet4() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet5() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Digest");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet6() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Basic realm=bar");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet7() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Basic realm=bar");
    realmToAnswer = "bar";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet8() throws UnknownHostException {
    challenges = singletonList("Digest,Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet9() throws UnknownHostException {
    challenges = singletonList("Digest,Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet10() throws UnknownHostException {
    challenges = singletonList("Digest, Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet11() throws UnknownHostException {
    challenges = singletonList("Digest, Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet12() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet13() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,foo=bar,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet14() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,foo=bar,realm=\"f,oo\"");
    realmToAnswer = "f,oo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet15() throws UnknownHostException {
    challenges = singletonList(",Digest,,,, Basic ,,foo=bar,realm=\"f,oo\"");
    realmToAnswer = "f,oo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet16() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,,realm=\"f\\\\\\\"o\\o\"");
    realmToAnswer = "f\\\"oo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet17() throws UnknownHostException {
    challenges = asList("Digest", "Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet18() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Digest");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet19() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Basic realm=bar");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet20() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Basic realm=bar");
    realmToAnswer = "bar";
    proxyAuthenticate = true;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet21() throws UnknownHostException {
    challenges = singletonList(" ,  , Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet22() throws UnknownHostException {
    challenges = singletonList("Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet23() throws UnknownHostException {
    challenges = singletonList("Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet24() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet25() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Digest");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet26() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Basic realm=bar");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet27() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Basic realm=bar");
    realmToAnswer = "bar";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet28() throws UnknownHostException {
    challenges = singletonList("Digest,Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet29() throws UnknownHostException {
    challenges = singletonList("Digest,Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet30() throws UnknownHostException {
    challenges = singletonList("Digest, Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet31() throws UnknownHostException {
    challenges = singletonList("Digest, Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet32() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet33() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,foo=bar,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet34() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,foo=bar,realm=\"f,oo\"");
    realmToAnswer = "f,oo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet35() throws UnknownHostException {
    challenges = singletonList(",Digest,,,, Basic ,,foo=bar,realm=\"f,oo\"");
    realmToAnswer = "f,oo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet36() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,,realm=\"f\\\\\\\"o\\o\"");
    realmToAnswer = "f\\\"oo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet37() throws UnknownHostException {
    challenges = asList("Digest", "Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet38() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Digest");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet39() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Basic realm=bar");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void correctAuthenticationHeaderIsSet40() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Basic realm=bar");
    realmToAnswer = "bar";
    proxyAuthenticate = false;
    correctAuthenticationHeaderIsSet();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow1() throws UnknownHostException {
    challenges = singletonList(" ,  , Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow2() throws UnknownHostException {
    challenges = singletonList("Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow3() throws UnknownHostException {
    challenges = singletonList("Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow4() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow5() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Digest");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow6() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Basic realm=bar");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow7() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Basic realm=bar");
    realmToAnswer = "bar";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow8() throws UnknownHostException {
    challenges = singletonList("Digest,Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow9() throws UnknownHostException {
    challenges = singletonList("Digest,Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow10() throws UnknownHostException {
    challenges = singletonList("Digest, Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow11() throws UnknownHostException {
    challenges = singletonList("Digest, Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow12() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow13() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,foo=bar,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow14() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,foo=bar,realm=\"f,oo\"");
    realmToAnswer = "f,oo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow15() throws UnknownHostException {
    challenges = singletonList(",Digest,,,, Basic ,,foo=bar,realm=\"f,oo\"");
    realmToAnswer = "f,oo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow16() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,,realm=\"f\\\\\\\"o\\o\"");
    realmToAnswer = "f\\\"oo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow17() throws UnknownHostException {
    challenges = asList("Digest", "Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow18() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Digest");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow19() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Basic realm=bar");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow20() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Basic realm=bar");
    realmToAnswer = "bar";
    proxyAuthenticate = true;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow21() throws UnknownHostException {
    challenges = singletonList(" ,  , Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow22() throws UnknownHostException {
    challenges = singletonList("Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow23() throws UnknownHostException {
    challenges = singletonList("Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow24() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow25() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Digest");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow26() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Basic realm=bar");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow27() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Basic realm=bar");
    realmToAnswer = "bar";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow28() throws UnknownHostException {
    challenges = singletonList("Digest,Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow29() throws UnknownHostException {
    challenges = singletonList("Digest,Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow30() throws UnknownHostException {
    challenges = singletonList("Digest, Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow31() throws UnknownHostException {
    challenges = singletonList("Digest, Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow32() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow33() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,foo=bar,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow34() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,foo=bar,realm=\"f,oo\"");
    realmToAnswer = "f,oo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow35() throws UnknownHostException {
    challenges = singletonList(",Digest,,,, Basic ,,foo=bar,realm=\"f,oo\"");
    realmToAnswer = "f,oo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow36() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,,realm=\"f\\\\\\\"o\\o\"");
    realmToAnswer = "f\\\"oo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow37() throws UnknownHostException {
    challenges = asList("Digest", "Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow38() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Digest");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow39() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Basic realm=bar");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void credentialsAreNotRetriedTwiceInARow40() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Basic realm=bar");
    realmToAnswer = "bar";
    proxyAuthenticate = false;
    credentialsAreNotRetriedTwiceInARow();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork1() throws UnknownHostException {
    challenges = singletonList(" ,  , Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork2() throws UnknownHostException {
    challenges = singletonList("Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork3() throws UnknownHostException {
    challenges = singletonList("Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork4() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork5() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Digest");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork6() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Basic realm=bar");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork7() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Basic realm=bar");
    realmToAnswer = "bar";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork8() throws UnknownHostException {
    challenges = singletonList("Digest,Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork9() throws UnknownHostException {
    challenges = singletonList("Digest,Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork10() throws UnknownHostException {
    challenges = singletonList("Digest, Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork11() throws UnknownHostException {
    challenges = singletonList("Digest, Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork12() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork13() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,foo=bar,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork14() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,foo=bar,realm=\"f,oo\"");
    realmToAnswer = "f,oo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork15() throws UnknownHostException {
    challenges = singletonList(",Digest,,,, Basic ,,foo=bar,realm=\"f,oo\"");
    realmToAnswer = "f,oo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork16() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,,realm=\"f\\\\\\\"o\\o\"");
    realmToAnswer = "f\\\"oo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork17() throws UnknownHostException {
    challenges = asList("Digest", "Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork18() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Digest");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork19() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Basic realm=bar");
    realmToAnswer = "foo";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork20() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Basic realm=bar");
    realmToAnswer = "bar";
    proxyAuthenticate = true;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork21() throws UnknownHostException {
    challenges = singletonList(" ,  , Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork22() throws UnknownHostException {
    challenges = singletonList("Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork23() throws UnknownHostException {
    challenges = singletonList("Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork24() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork25() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Digest");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork26() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Basic realm=bar");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork27() throws UnknownHostException {
    challenges = singletonList("Basic realm = \"foo\",Basic realm=bar");
    realmToAnswer = "bar";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork28() throws UnknownHostException {
    challenges = singletonList("Digest,Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork29() throws UnknownHostException {
    challenges = singletonList("Digest,Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork30() throws UnknownHostException {
    challenges = singletonList("Digest, Basic realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork31() throws UnknownHostException {
    challenges = singletonList("Digest, Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork32() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork33() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,foo=bar,realm=\"foo\"");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork34() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,foo=bar,realm=\"f,oo\"");
    realmToAnswer = "f,oo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork35() throws UnknownHostException {
    challenges = singletonList(",Digest,,,, Basic ,,foo=bar,realm=\"f,oo\"");
    realmToAnswer = "f,oo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork36() throws UnknownHostException {
    challenges = singletonList("Digest,,,, Basic ,,,realm=\"f\\\\\\\"o\\o\"");
    realmToAnswer = "f\\\"oo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork37() throws UnknownHostException {
    challenges = asList("Digest", "Basic realm=foo");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork38() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Digest");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork39() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Basic realm=bar");
    realmToAnswer = "foo";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }

  @Test public void newCredentialsAreTriedIfOthersDidNotWork40() throws UnknownHostException {
    challenges = asList("Basic realm=foo", "Basic realm=bar");
    realmToAnswer = "bar";
    proxyAuthenticate = false;
    newCredentialsAreTriedIfOthersDidNotWork();
  }
}
