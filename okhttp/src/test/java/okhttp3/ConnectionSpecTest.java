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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.junit.Test;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class ConnectionSpecTest {
  @Test public void noTlsVersions() throws Exception {
    try {
      new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
          .tlsVersions(new TlsVersion[0])
          .build();
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("At least one TLS version is required");
    }
  }

  @Test public void noCipherSuites() throws Exception {
    try {
      new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
          .cipherSuites(new CipherSuite[0])
          .build();
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("At least one cipher suite is required");
    }
  }

  @Test public void cleartextBuilder() throws Exception {
    ConnectionSpec cleartextSpec = new ConnectionSpec.Builder(false).build();
    assertThat(cleartextSpec.isTls()).isFalse();
  }

  @Test public void tlsBuilder_explicitCiphers() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(true)
        .build();
    assertThat(tlsSpec.cipherSuites()).containsExactly(CipherSuite.TLS_RSA_WITH_RC4_128_MD5);
    assertThat(tlsSpec.tlsVersions()).containsExactly(TlsVersion.TLS_1_2);
    assertThat(tlsSpec.supportsTlsExtensions()).isTrue();
  }

  @Test public void tlsBuilder_defaultCiphers() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(true)
        .build();
    assertThat(tlsSpec.cipherSuites()).isNull();
    assertThat(tlsSpec.tlsVersions()).containsExactly(TlsVersion.TLS_1_2);
    assertThat(tlsSpec.supportsTlsExtensions()).isTrue();
  }

  @Test public void tls_defaultCiphers_noFallbackIndicator() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build();

    SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    socket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName(),
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName(),
    });
    socket.setEnabledProtocols(new String[] {
        TlsVersion.TLS_1_2.javaName(),
        TlsVersion.TLS_1_1.javaName(),
    });

    assertThat(tlsSpec.isCompatible(socket)).isTrue();
    tlsSpec.apply(socket, false /* isFallback */);

    assertThat(socket.getEnabledProtocols()).containsExactly(TlsVersion.TLS_1_2.javaName());

    assertThat(socket.getEnabledCipherSuites()).containsExactlyInAnyOrder(
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName(),
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName());
  }

  @Test public void tls_defaultCiphers_withFallbackIndicator() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build();

    SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    socket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName(),
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName(),
    });
    socket.setEnabledProtocols(new String[] {
        TlsVersion.TLS_1_2.javaName(),
        TlsVersion.TLS_1_1.javaName(),
    });

    assertThat(tlsSpec.isCompatible(socket)).isTrue();
    tlsSpec.apply(socket, true /* isFallback */);

    assertThat(socket.getEnabledProtocols()).containsExactly(TlsVersion.TLS_1_2.javaName());

    List<String> expectedCipherSuites = new ArrayList<>();
    expectedCipherSuites.add(CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName());
    expectedCipherSuites.add(CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName());
    if (asList(socket.getSupportedCipherSuites()).contains("TLS_FALLBACK_SCSV")) {
      expectedCipherSuites.add("TLS_FALLBACK_SCSV");
    }
    assertThat(socket.getEnabledCipherSuites()).containsExactlyElementsOf(expectedCipherSuites);
  }

  @Test public void tls_explicitCiphers() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build();

    SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    socket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName(),
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName(),
    });
    socket.setEnabledProtocols(new String[] {
        TlsVersion.TLS_1_2.javaName(),
        TlsVersion.TLS_1_1.javaName(),
    });

    assertThat(tlsSpec.isCompatible(socket)).isTrue();
    tlsSpec.apply(socket, true /* isFallback */);

    assertThat(socket.getEnabledProtocols()).containsExactly(TlsVersion.TLS_1_2.javaName());

    List<String> expectedCipherSuites = new ArrayList<>();
    expectedCipherSuites.add(CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName());
    if (asList(socket.getSupportedCipherSuites()).contains("TLS_FALLBACK_SCSV")) {
      expectedCipherSuites.add("TLS_FALLBACK_SCSV");
    }
    assertThat(socket.getEnabledCipherSuites()).containsExactlyElementsOf(expectedCipherSuites);
  }

  @Test public void tls_stringCiphersAndVersions() throws Exception {
    // Supporting arbitrary input strings allows users to enable suites and versions that are not
    // yet known to the library, but are supported by the platform.
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .cipherSuites("MAGIC-CIPHER")
        .tlsVersions("TLS9k")
        .build();
  }

  @Test public void tls_missingRequiredCipher() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build();

    SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    socket.setEnabledProtocols(new String[] {
        TlsVersion.TLS_1_2.javaName(),
        TlsVersion.TLS_1_1.javaName(),
    });

    socket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName(),
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName(),
    });
    assertThat(tlsSpec.isCompatible(socket)).isTrue();

    socket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName(),
    });
    assertThat(tlsSpec.isCompatible(socket)).isFalse();
  }

  @Test public void allEnabledCipherSuites() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledCipherSuites()
        .build();
    assertThat(tlsSpec.cipherSuites()).isNull();

    SSLSocket sslSocket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    sslSocket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName(),
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName(),
    });

    tlsSpec.apply(sslSocket, false);
    assertThat(sslSocket.getEnabledCipherSuites()).containsExactly(
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName(),
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName());
  }

  @Test public void allEnabledTlsVersions() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledTlsVersions()
        .build();
    assertThat(tlsSpec.tlsVersions()).isNull();

    SSLSocket sslSocket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    sslSocket.setEnabledProtocols(new String[] {
        TlsVersion.SSL_3_0.javaName(),
        TlsVersion.TLS_1_1.javaName()
    });

    tlsSpec.apply(sslSocket, false);
    assertThat(sslSocket.getEnabledProtocols()).containsExactly(
        TlsVersion.SSL_3_0.javaName(), TlsVersion.TLS_1_1.javaName());
  }

  @Test public void tls_missingTlsVersion() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build();

    SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    socket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName(),
    });

    socket.setEnabledProtocols(
        new String[] {TlsVersion.TLS_1_2.javaName(), TlsVersion.TLS_1_1.javaName()});
    assertThat(tlsSpec.isCompatible(socket)).isTrue();

    socket.setEnabledProtocols(new String[] {TlsVersion.TLS_1_1.javaName()});
    assertThat(tlsSpec.isCompatible(socket)).isFalse();
  }

  @Test public void equalsAndHashCode() throws Exception {
    ConnectionSpec allCipherSuites = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledCipherSuites()
        .build();
    ConnectionSpec allTlsVersions = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledTlsVersions()
        .build();

    Set<Object> set = new CopyOnWriteArraySet<>();
    assertThat(set.add(ConnectionSpec.MODERN_TLS)).isTrue();
    assertThat(set.add(ConnectionSpec.COMPATIBLE_TLS)).isTrue();
    assertThat(set.add(ConnectionSpec.CLEARTEXT)).isTrue();
    assertThat(set.add(allTlsVersions)).isTrue();
    assertThat(set.add(allCipherSuites)).isTrue();

    assertThat(set.remove(ConnectionSpec.MODERN_TLS)).isTrue();
    assertThat(set.remove(ConnectionSpec.COMPATIBLE_TLS)).isTrue();
    assertThat(set.remove(ConnectionSpec.CLEARTEXT)).isTrue();
    assertThat(set.remove(allTlsVersions)).isTrue();
    assertThat(set.remove(allCipherSuites)).isTrue();
    assertThat(set).isEmpty();
  }

  @Test public void allEnabledToString() throws Exception {
    ConnectionSpec connectionSpec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledTlsVersions()
        .allEnabledCipherSuites()
        .build();
    assertThat(connectionSpec.toString()).isEqualTo(
        ("ConnectionSpec(cipherSuites=[all enabled], tlsVersions=[all enabled], "
        + "supportsTlsExtensions=true)"));
  }

  @Test public void simpleToString() throws Exception {
    ConnectionSpec connectionSpec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_2)
        .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        .build();
    assertThat(connectionSpec.toString()).isEqualTo(
        ("ConnectionSpec(cipherSuites=[SSL_RSA_WITH_RC4_128_MD5], tlsVersions=[TLS_1_2], "
        + "supportsTlsExtensions=true)"));
  }
}
