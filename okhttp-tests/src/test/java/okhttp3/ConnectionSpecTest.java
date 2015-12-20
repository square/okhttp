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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ConnectionSpecTest {
  @Test public void noTlsVersions() throws Exception {
    try {
      new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
          .tlsVersions(new TlsVersion[0])
          .build();
      fail();
    } catch (IllegalArgumentException expected) {
      assertEquals("At least one TLS version is required", expected.getMessage());
    }
  }

  @Test public void noCipherSuites() throws Exception {
    try {
      new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
          .cipherSuites(new CipherSuite[0])
          .build();
      fail();
    } catch (IllegalArgumentException expected) {
      assertEquals("At least one cipher suite is required", expected.getMessage());
    }
  }

  @Test public void cleartextBuilder() throws Exception {
    ConnectionSpec cleartextSpec = new ConnectionSpec.Builder(false).build();
    assertFalse(cleartextSpec.isTls());
  }

  @Test public void tlsBuilder_explicitCiphers() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(true)
        .build();
    assertEquals(Arrays.asList(CipherSuite.TLS_RSA_WITH_RC4_128_MD5), tlsSpec.cipherSuites());
    assertEquals(Arrays.asList(TlsVersion.TLS_1_2), tlsSpec.tlsVersions());
    assertTrue(tlsSpec.supportsTlsExtensions());
  }

  @Test public void tlsBuilder_defaultCiphers() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(true)
        .build();
    assertNull(tlsSpec.cipherSuites());
    assertEquals(Arrays.asList(TlsVersion.TLS_1_2), tlsSpec.tlsVersions());
    assertTrue(tlsSpec.supportsTlsExtensions());
  }

  @Test public void tls_defaultCiphers_noFallbackIndicator() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build();

    SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    socket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName,
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName,
    });
    socket.setEnabledProtocols(new String[] {
        TlsVersion.TLS_1_2.javaName,
        TlsVersion.TLS_1_1.javaName,
    });

    assertTrue(tlsSpec.isCompatible(socket));
    tlsSpec.apply(socket, false /* isFallback */);

    assertEquals(set(TlsVersion.TLS_1_2.javaName), set(socket.getEnabledProtocols()));

    Set<String> expectedCipherSet =
        set(
            CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName,
            CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName);
    assertEquals(expectedCipherSet, expectedCipherSet);
  }

  @Test public void tls_defaultCiphers_withFallbackIndicator() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build();

    SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    socket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName,
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName,
    });
    socket.setEnabledProtocols(new String[] {
        TlsVersion.TLS_1_2.javaName,
        TlsVersion.TLS_1_1.javaName,
    });

    assertTrue(tlsSpec.isCompatible(socket));
    tlsSpec.apply(socket, true /* isFallback */);

    assertEquals(set(TlsVersion.TLS_1_2.javaName), set(socket.getEnabledProtocols()));

    Set<String> expectedCipherSet =
        set(
            CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName,
            CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName);
    if (Arrays.asList(socket.getSupportedCipherSuites()).contains("TLS_FALLBACK_SCSV")) {
      expectedCipherSet.add("TLS_FALLBACK_SCSV");
    }
    assertEquals(expectedCipherSet, expectedCipherSet);
  }

  @Test public void tls_explicitCiphers() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build();

    SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    socket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName,
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName,
    });
    socket.setEnabledProtocols(new String[] {
        TlsVersion.TLS_1_2.javaName,
        TlsVersion.TLS_1_1.javaName,
    });

    assertTrue(tlsSpec.isCompatible(socket));
    tlsSpec.apply(socket, true /* isFallback */);

    assertEquals(set(TlsVersion.TLS_1_2.javaName), set(socket.getEnabledProtocols()));

    Set<String> expectedCipherSet = set(CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName);
    if (Arrays.asList(socket.getSupportedCipherSuites()).contains("TLS_FALLBACK_SCSV")) {
      expectedCipherSet.add("TLS_FALLBACK_SCSV");
    }
    assertEquals(expectedCipherSet, expectedCipherSet);
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
        TlsVersion.TLS_1_2.javaName,
        TlsVersion.TLS_1_1.javaName,
    });

    socket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName,
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName,
    });
    assertTrue(tlsSpec.isCompatible(socket));

    socket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName,
    });
    assertFalse(tlsSpec.isCompatible(socket));
  }

  @Test public void allEnabledCipherSuites() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledCipherSuites()
        .build();
    assertNull(tlsSpec.cipherSuites());

    SSLSocket sslSocket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    sslSocket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName,
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName,
    });

    tlsSpec.apply(sslSocket, false);
    assertEquals(Arrays.asList(
            CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName,
            CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName),
        Arrays.asList(sslSocket.getEnabledCipherSuites()));
  }

  @Test public void allEnabledTlsVersions() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledTlsVersions()
        .build();
    assertNull(tlsSpec.tlsVersions());

    SSLSocket sslSocket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    sslSocket.setEnabledProtocols(new String[] {
        TlsVersion.SSL_3_0.javaName(),
        TlsVersion.TLS_1_1.javaName()
    });

    tlsSpec.apply(sslSocket, false);
    assertEquals(Arrays.asList(TlsVersion.SSL_3_0.javaName(), TlsVersion.TLS_1_1.javaName()),
        Arrays.asList(sslSocket.getEnabledProtocols()));
  }

  @Test public void tls_missingTlsVersion() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build();

    SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    socket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName,
    });

    socket.setEnabledProtocols(
        new String[] { TlsVersion.TLS_1_2.javaName, TlsVersion.TLS_1_1.javaName });
    assertTrue(tlsSpec.isCompatible(socket));

    socket.setEnabledProtocols(new String[] { TlsVersion.TLS_1_1.javaName });
    assertFalse(tlsSpec.isCompatible(socket));
  }

  @Test public void equalsAndHashCode() throws Exception {
    ConnectionSpec allCipherSuites = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledCipherSuites()
        .build();
    ConnectionSpec allTlsVersions = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledTlsVersions()
        .build();

    Set<Object> set = new CopyOnWriteArraySet<>();
    assertTrue(set.add(ConnectionSpec.MODERN_TLS));
    assertTrue(set.add(ConnectionSpec.COMPATIBLE_TLS));
    assertTrue(set.add(ConnectionSpec.CLEARTEXT));
    assertTrue(set.add(allTlsVersions));
    assertTrue(set.add(allCipherSuites));

    assertTrue(set.remove(ConnectionSpec.MODERN_TLS));
    assertTrue(set.remove(ConnectionSpec.COMPATIBLE_TLS));
    assertTrue(set.remove(ConnectionSpec.CLEARTEXT));
    assertTrue(set.remove(allTlsVersions));
    assertTrue(set.remove(allCipherSuites));
    assertTrue(set.isEmpty());
  }

  @Test public void allEnabledToString() throws Exception {
    ConnectionSpec connectionSpec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledTlsVersions()
        .allEnabledCipherSuites()
        .build();
    assertEquals("ConnectionSpec(cipherSuites=[all enabled], tlsVersions=[all enabled], "
        + "supportsTlsExtensions=true)", connectionSpec.toString());
  }

  @Test public void simpleToString() throws Exception {
    ConnectionSpec connectionSpec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_2)
        .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        .build();
    assertEquals("ConnectionSpec(cipherSuites=[TLS_RSA_WITH_RC4_128_MD5], tlsVersions=[TLS_1_2], "
        + "supportsTlsExtensions=true)", connectionSpec.toString());
  }

  private static <T> Set<T> set(T... values) {
    return new LinkedHashSet<>(Arrays.asList(values));
  }
}
