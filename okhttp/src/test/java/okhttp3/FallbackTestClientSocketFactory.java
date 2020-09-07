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
package okhttp3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * An SSLSocketFactory that delegates calls. Sockets created by the delegate are wrapped with ones
 * that will not accept the {@link #TLS_FALLBACK_SCSV} cipher, thus bypassing server-side fallback
 * checks on platforms that support it. Unfortunately this wrapping will disable any
 * reflection-based calls to SSLSocket from Platform.
 */
public class FallbackTestClientSocketFactory extends DelegatingSSLSocketFactory {
  /**
   * The cipher suite used during TLS connection fallback to indicate a fallback. See
   * https://tools.ietf.org/html/draft-ietf-tls-downgrade-scsv-00
   */
  public static final String TLS_FALLBACK_SCSV = "TLS_FALLBACK_SCSV";

  public FallbackTestClientSocketFactory(SSLSocketFactory delegate) {
    super(delegate);
  }

  @Override protected SSLSocket configureSocket(SSLSocket sslSocket) throws IOException {
    return new TlsFallbackScsvDisabledSSLSocket(sslSocket);
  }

  private static class TlsFallbackScsvDisabledSSLSocket extends DelegatingSSLSocket {

    public TlsFallbackScsvDisabledSSLSocket(SSLSocket socket) {
      super(socket);
    }

    @Override public void setEnabledCipherSuites(String[] suites) {
      List<String> enabledCipherSuites = new ArrayList<>(suites.length);
      for (String suite : suites) {
        if (!suite.equals(TLS_FALLBACK_SCSV)) {
          enabledCipherSuites.add(suite);
        }
      }
      delegate.setEnabledCipherSuites(
          enabledCipherSuites.toArray(new String[enabledCipherSuites.size()]));
    }
  }
}
