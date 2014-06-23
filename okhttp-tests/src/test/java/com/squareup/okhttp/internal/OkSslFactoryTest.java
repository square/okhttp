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
import org.junit.Assert;
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

public class OkSslFactoryTest {

  private MockWebServer server = new MockWebServer();
  private SSLSocketFactory socketFactory;

  public OkSslFactoryTest() throws KeyManagementException, NoSuchAlgorithmException, IOException {
    socketFactory = getSSLContext().getSocketFactory();
    server.play();
  }

  @Test
  public void createSocketWithIntersectedProtocols() throws IOException {
    String[] defaultEnabledProtocols = new String[] {"TLSv1", "TLSv1.2"};

    OkHttpSslFactory okHttpSslFactory = new OkHttpSslFactory(socketFactory, defaultEnabledProtocols);

    Assert.assertArrayEquals(defaultEnabledProtocols,  getEnabledProtocols(okHttpSslFactory));
  }

  @Test
  public void createSocketWithIntersectedCiphers() throws IOException {
    OkHttpSslFactory okHttpSslFactory = new OkHttpSslFactory(socketFactory, new String[]{});

    String[] decoratedDefaultCipherSuites = okHttpSslFactory.getDefaultCipherSuites();

    List<String> decoratedList = new ArrayList<String>(Arrays.asList(decoratedDefaultCipherSuites));
    decoratedList.removeAll(Arrays.asList(OkHttpSslFactory.ENABLED_CIPHERS));
    Assert.assertTrue(decoratedList.isEmpty());
  }

  private static SSLContext getSSLContext() throws NoSuchAlgorithmException, KeyManagementException {
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, null, null);
    return sslContext;
  }

  private String[] getEnabledProtocols(SSLSocketFactory sslSocketFactory) throws IOException {
    return ((SSLSocket) sslSocketFactory.createSocket(server.getUrl("/").getHost(),
            server.getPort()))
            .getEnabledProtocols();
  }

}
