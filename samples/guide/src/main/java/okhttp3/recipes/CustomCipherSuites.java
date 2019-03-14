/*
 * Copyright (C) 2017 Square, Inc.
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
package okhttp3.recipes;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.CipherSuite;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static java.util.Arrays.asList;

public final class CustomCipherSuites {
  private final OkHttpClient client;

  public CustomCipherSuites() throws GeneralSecurityException {
    // Configure cipher suites to demonstrate how to customize which cipher suites will be used for
    // an OkHttp request. In order to be selected a cipher suite must be included in both OkHttp's
    // connection spec and in the SSLSocket's enabled cipher suites array. Most applications should
    // not customize the cipher suites list.
    List<CipherSuite> customCipherSuites = asList(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384);
    final ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .cipherSuites(customCipherSuites.toArray(new CipherSuite[0]))
        .build();

    X509TrustManager trustManager = defaultTrustManager();
    SSLSocketFactory sslSocketFactory = defaultSslSocketFactory(trustManager);
    SSLSocketFactory customSslSocketFactory = new DelegatingSSLSocketFactory(sslSocketFactory) {
      @Override protected SSLSocket configureSocket(SSLSocket socket) throws IOException {
        socket.setEnabledCipherSuites(javaNames(spec.cipherSuites()));
        return socket;
      }
    };

    client = new OkHttpClient.Builder()
        .connectionSpecs(Collections.singletonList(spec))
        .sslSocketFactory(customSslSocketFactory, trustManager)
        .build();
  }

  /**
   * Returns the VM's default SSL socket factory, using {@code trustManager} for trusted root
   * certificates.
   */
  private SSLSocketFactory defaultSslSocketFactory(X509TrustManager trustManager)
      throws NoSuchAlgorithmException, KeyManagementException {
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, new TrustManager[] { trustManager }, null);

    return sslContext.getSocketFactory();
  }

  /** Returns a trust manager that trusts the VM's default certificate authorities. */
  private X509TrustManager defaultTrustManager() throws GeneralSecurityException {
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init((KeyStore) null);
    TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
    if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
      throw new IllegalStateException("Unexpected default trust managers:"
          + Arrays.toString(trustManagers));
    }
    return (X509TrustManager) trustManagers[0];
  }

  private String[] javaNames(List<CipherSuite> cipherSuites) {
    String[] result = new String[cipherSuites.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = cipherSuites.get(i).javaName();
    }
    return result;
  }

  /**
   * An SSL socket factory that forwards all calls to a delegate. Override {@link #configureSocket}
   * to customize a created socket before it is returned.
   */
  static class DelegatingSSLSocketFactory extends SSLSocketFactory {
    protected final SSLSocketFactory delegate;

    DelegatingSSLSocketFactory(SSLSocketFactory delegate) {
      this.delegate = delegate;
    }

    @Override public String[] getDefaultCipherSuites() {
      return delegate.getDefaultCipherSuites();
    }

    @Override public String[] getSupportedCipherSuites() {
      return delegate.getSupportedCipherSuites();
    }

    @Override public Socket createSocket(
        Socket socket, String host, int port, boolean autoClose) throws IOException {
      return configureSocket((SSLSocket) delegate.createSocket(socket, host, port, autoClose));
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
      return configureSocket((SSLSocket) delegate.createSocket(host, port));
    }

    @Override public Socket createSocket(
        String host, int port, InetAddress localHost, int localPort) throws IOException {
      return configureSocket((SSLSocket) delegate.createSocket(host, port, localHost, localPort));
    }

    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
      return configureSocket((SSLSocket) delegate.createSocket(host, port));
    }

    @Override public Socket createSocket(
        InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
      return configureSocket((SSLSocket) delegate.createSocket(
          address, port, localAddress, localPort));
    }

    protected SSLSocket configureSocket(SSLSocket socket) throws IOException {
      return socket;
    }
  }

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("https://publicobject.com/helloworld.txt")
        .build();

    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

      System.out.println(response.handshake().cipherSuite());
      System.out.println(response.body().string());
    }
  }

  public static void main(String... args) throws Exception {
    new CustomCipherSuites().run();
  }
}
