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
package com.squareup.okhttp;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ConnectionSpecTest {

  @Test
  public void cleartextBuilder() throws Exception {
    ConnectionSpec cleartextSpec = new ConnectionSpec.Builder(false).build();
    assertFalse(cleartextSpec.isTls());
  }

  @Test
  public void tlsBuilder_explicitCiphers() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(true)
        .build();
    assertEquals(Arrays.asList(CipherSuite.TLS_RSA_WITH_RC4_128_MD5), tlsSpec.cipherSuites());
    assertEquals(Arrays.asList(TlsVersion.TLS_1_2), tlsSpec.tlsVersions());
    assertTrue(tlsSpec.supportsTlsExtensions());
  }

  @Test
  public void tlsBuilder_defaultCiphers() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(true)
        .build();
    assertNull(tlsSpec.cipherSuites());
    assertEquals(Arrays.asList(TlsVersion.TLS_1_2), tlsSpec.tlsVersions());
    assertTrue(tlsSpec.supportsTlsExtensions());
  }

  @Test
  public void tls_defaultCiphers_noFallbackIndicator() throws Exception {
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

    assertEquals(createSet(TlsVersion.TLS_1_2.javaName), createSet(socket.getEnabledProtocols()));

    Set<String> expectedCipherSet =
        createSet(
            CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName,
            CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName);
    assertEquals(expectedCipherSet, expectedCipherSet);
  }

  @Test
  public void tls_defaultCiphers_withFallbackIndicator() throws Exception {
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

    assertEquals(createSet(TlsVersion.TLS_1_2.javaName), createSet(socket.getEnabledProtocols()));

    Set<String> expectedCipherSet =
        createSet(
            CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName,
            CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName);
    if (Arrays.asList(socket.getSupportedCipherSuites()).contains("TLS_FALLBACK_SCSV")) {
      expectedCipherSet.add("TLS_FALLBACK_SCSV");
    }
    assertEquals(expectedCipherSet, expectedCipherSet);
  }

  @Test
  public void tls_explicitCiphers() throws Exception {
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

    assertEquals(createSet(TlsVersion.TLS_1_2.javaName), createSet(socket.getEnabledProtocols()));

    Set<String> expectedCipherSet = createSet(CipherSuite.TLS_RSA_WITH_RC4_128_MD5.javaName);
    if (Arrays.asList(socket.getSupportedCipherSuites()).contains("TLS_FALLBACK_SCSV")) {
      expectedCipherSet.add("TLS_FALLBACK_SCSV");
    }
    assertEquals(expectedCipherSet, expectedCipherSet);
  }

  @Test
  public void tls_missingRequiredCipher() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build();

    SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    socket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName,
    });
    socket.setEnabledProtocols(new String[] {
        TlsVersion.TLS_1_2.javaName,
        TlsVersion.TLS_1_1.javaName,
    });

    assertFalse(tlsSpec.isCompatible(socket));
  }

  @Test
  public void tls_missingPrimaryTlsVersion() throws Exception {
    ConnectionSpec tlsSpec = new ConnectionSpec.Builder(true)
        .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1)
        .supportsTlsExtensions(false)
        .build();

    SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    socket.setEnabledCipherSuites(new String[] {
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA.javaName,
    });
    // Only the first protocol is considered.
    socket.setEnabledProtocols(new String[] { TlsVersion.TLS_1_1.javaName });

    assertFalse(tlsSpec.isCompatible(socket));
  }

  private static Set<String> createSet(String... values) {
    return new LinkedHashSet<String>(Arrays.asList(values));
  }
}
