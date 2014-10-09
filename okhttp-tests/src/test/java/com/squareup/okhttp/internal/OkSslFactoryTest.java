/*
 * Copyright 2014 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp.internal;

import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class OkSslFactoryTest {

  @Rule public MockWebServerRule serverRule = new MockWebServerRule();

  private SSLSocketFactory socketFactory;
  private MockWebServer server;

  @Before
  public void setUp() throws Exception {
    server = serverRule.get();
  }

  public OkSslFactoryTest() throws KeyManagementException, NoSuchAlgorithmException, IOException {
    socketFactory = getAndInitSSLContext().getSocketFactory();
  }

  @Test
  public void createSocketWithIntersectedProtocols() throws IOException {
    String[] defaultEnabledProtocols = new String[] {"TLSv1", "TLSv1.2"};

    OkHttpSslFactory okHttpSslFactory = new OkHttpSslFactory(socketFactory, defaultEnabledProtocols);

    assertArrayEquals(defaultEnabledProtocols, getEnabledProtocols(okHttpSslFactory));
  }

  @Test
  public void createSocketWithIntersectedCiphersAndVerifyOrder() throws IOException {
    String[] decoratedDefaultCipherSuites = new OkHttpSslFactory(socketFactory, new String[0])
        .getDefaultCipherSuites();

    List<String> decoratedList = Arrays.asList(decoratedDefaultCipherSuites);
    List<String> defaultList = new ArrayList<String>(Arrays.asList(OkHttpSslFactory.ENABLED_CIPHERS));
    defaultList.retainAll(decoratedList);

    assertEquals(defaultList, decoratedList);
  }

  private static SSLContext getAndInitSSLContext() throws NoSuchAlgorithmException, KeyManagementException {
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, null, null);
    return sslContext;
  }

  private String[] getEnabledProtocols(SSLSocketFactory sslSocketFactory) throws IOException {
    SSLSocket socket = null;
    try {
      socket = (SSLSocket) sslSocketFactory.createSocket(server.getUrl("/").getHost(),
          server.getPort());
      return socket.getEnabledProtocols();
    } finally {
      if(socket != null){
        socket.close();
      }
    }
  }

}
