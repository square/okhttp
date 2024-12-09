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
package okhttp3

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.util.concurrent.CopyOnWriteArraySet
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.test.assertFailsWith
import okhttp3.internal.applyConnectionSpec
import okhttp3.internal.platform.Platform.Companion.isAndroid
import okhttp3.testing.PlatformRule
import okhttp3.testing.PlatformVersion.majorVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class ConnectionSpecTest {
  @RegisterExtension
  val platform = PlatformRule()

  @Test
  fun noTlsVersions() {
    assertFailsWith<IllegalArgumentException> {
      ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(*arrayOf<String>())
        .build()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("At least one TLS version is required")
    }
  }

  @Test
  fun noCipherSuites() {
    assertFailsWith<IllegalArgumentException> {
      ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .cipherSuites(*arrayOf<CipherSuite>())
        .build()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("At least one cipher suite is required")
    }
  }

  @Test
  fun cleartextBuilder() {
    val cleartextSpec = ConnectionSpec.Builder(false).build()
    assertThat(cleartextSpec.isTls).isFalse()
  }

  @Test
  fun tlsBuilder_explicitCiphers() {
    val tlsSpec =
      ConnectionSpec.Builder(true)
        .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(true)
        .build()
    assertThat(tlsSpec.cipherSuites!!.toList())
      .containsExactly(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
    assertThat(tlsSpec.tlsVersions!!.toList())
      .containsExactly(TlsVersion.TLS_1_2)
    assertThat(tlsSpec.supportsTlsExtensions).isTrue()
  }

  @Test
  fun tlsBuilder_defaultCiphers() {
    val tlsSpec =
      ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(true)
        .build()
    assertThat(tlsSpec.cipherSuites).isNull()
    assertThat(tlsSpec.tlsVersions!!.toList())
      .containsExactly(TlsVersion.TLS_1_2)
    assertThat(tlsSpec.supportsTlsExtensions).isTrue()
  }

  @Test
  fun tls_defaultCiphers_noFallbackIndicator() {
    platform.assumeNotConscrypt()
    platform.assumeNotBouncyCastle()
    val tlsSpec =
      ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build()
    val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
    socket.enabledCipherSuites =
      arrayOf(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
      )
    socket.enabledProtocols =
      arrayOf(
        TlsVersion.TLS_1_2.javaName,
        TlsVersion.TLS_1_1.javaName,
      )
    assertThat(tlsSpec.isCompatible(socket)).isTrue()
    applyConnectionSpec(tlsSpec, socket, isFallback = false)
    assertThat(socket.enabledProtocols).containsExactly(
      TlsVersion.TLS_1_2.javaName,
    )
    assertThat(socket.enabledCipherSuites.toList())
      .containsExactlyInAnyOrder(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
      )
  }

  @Test
  fun tls_defaultCiphers_withFallbackIndicator() {
    platform.assumeNotConscrypt()
    platform.assumeNotBouncyCastle()
    val tlsSpec =
      ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build()
    val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
    socket.enabledCipherSuites =
      arrayOf(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
      )
    socket.enabledProtocols =
      arrayOf(
        TlsVersion.TLS_1_2.javaName,
        TlsVersion.TLS_1_1.javaName,
      )
    assertThat(tlsSpec.isCompatible(socket)).isTrue()
    applyConnectionSpec(tlsSpec, socket, isFallback = true)
    assertThat(socket.enabledProtocols).containsExactly(
      TlsVersion.TLS_1_2.javaName,
    )
    val expectedCipherSuites: MutableList<String> = ArrayList()
    expectedCipherSuites.add(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName)
    expectedCipherSuites.add(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName)
    if (listOf<String>(*socket.supportedCipherSuites).contains("TLS_FALLBACK_SCSV")) {
      expectedCipherSuites.add("TLS_FALLBACK_SCSV")
    }
    assertThat(socket.enabledCipherSuites)
      .containsExactly(*expectedCipherSuites.toTypedArray())
  }

  @Test
  fun tls_explicitCiphers() {
    platform.assumeNotConscrypt()
    platform.assumeNotBouncyCastle()
    val tlsSpec =
      ConnectionSpec.Builder(true)
        .cipherSuites(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build()
    val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
    socket.enabledCipherSuites =
      arrayOf(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
      )
    socket.enabledProtocols =
      arrayOf(
        TlsVersion.TLS_1_2.javaName,
        TlsVersion.TLS_1_1.javaName,
      )
    assertThat(tlsSpec.isCompatible(socket)).isTrue()
    applyConnectionSpec(tlsSpec, socket, isFallback = true)
    assertThat(socket.enabledProtocols).containsExactly(
      TlsVersion.TLS_1_2.javaName,
    )
    val expectedCipherSuites: MutableList<String> = ArrayList()
    expectedCipherSuites.add(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName)
    if (listOf<String>(*socket.supportedCipherSuites).contains("TLS_FALLBACK_SCSV")) {
      expectedCipherSuites.add("TLS_FALLBACK_SCSV")
    }
    assertThat(socket.enabledCipherSuites)
      .containsExactly(*expectedCipherSuites.toTypedArray())
  }

  @Test
  fun tls_stringCiphersAndVersions() {
    // Supporting arbitrary input strings allows users to enable suites and versions that are not
    // yet known to the library, but are supported by the platform.
    ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
      .cipherSuites("MAGIC-CIPHER")
      .tlsVersions("TLS9k")
      .build()
  }

  @Test
  fun tls_missingRequiredCipher() {
    platform.assumeNotConscrypt()
    platform.assumeNotBouncyCastle()
    val tlsSpec =
      ConnectionSpec.Builder(true)
        .cipherSuites(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build()
    val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
    socket.enabledProtocols =
      arrayOf(
        TlsVersion.TLS_1_2.javaName,
        TlsVersion.TLS_1_1.javaName,
      )
    socket.enabledCipherSuites =
      arrayOf(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
      )
    assertThat(tlsSpec.isCompatible(socket)).isTrue()
    socket.enabledCipherSuites =
      arrayOf(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
      )
    assertThat(tlsSpec.isCompatible(socket)).isFalse()
  }

  @Test
  fun allEnabledCipherSuites() {
    platform.assumeNotConscrypt()
    platform.assumeNotBouncyCastle()
    val tlsSpec =
      ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledCipherSuites()
        .build()
    assertThat(tlsSpec.cipherSuites).isNull()
    val sslSocket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
    sslSocket.enabledCipherSuites =
      arrayOf(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
      )
    applyConnectionSpec(tlsSpec, sslSocket, false)
    if (platform.isAndroid) {
      // https://developer.android.com/reference/javax/net/ssl/SSLSocket
      val sdkVersion = platform.androidSdkVersion()
      if (sdkVersion != null && sdkVersion >= 29) {
        assertThat(sslSocket.enabledCipherSuites)
          .containsExactly(
            CipherSuite.TLS_AES_128_GCM_SHA256.javaName,
            CipherSuite.TLS_AES_256_GCM_SHA384.javaName,
            CipherSuite.TLS_CHACHA20_POLY1305_SHA256.javaName,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
          )
      } else {
        assertThat(sslSocket.enabledCipherSuites)
          .containsExactly(
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
          )
      }
    } else {
      assertThat(sslSocket.enabledCipherSuites)
        .containsExactly(
          CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
          CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
        )
    }
  }

  @Test
  fun allEnabledTlsVersions() {
    platform.assumeNotConscrypt()
    val tlsSpec =
      ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledTlsVersions()
        .build()
    assertThat(tlsSpec.tlsVersions).isNull()
    val sslSocket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
    if (majorVersion > 11) {
      sslSocket.enabledProtocols =
        arrayOf(
          TlsVersion.SSL_3_0.javaName,
          TlsVersion.TLS_1_1.javaName,
          TlsVersion.TLS_1_2.javaName,
          TlsVersion.TLS_1_3.javaName,
        )
    } else {
      sslSocket.enabledProtocols =
        arrayOf(
          TlsVersion.SSL_3_0.javaName,
          TlsVersion.TLS_1_1.javaName,
          TlsVersion.TLS_1_2.javaName,
        )
    }
    applyConnectionSpec(tlsSpec, sslSocket, false)
    if (isAndroid) {
      val sdkVersion = platform.androidSdkVersion()
      // https://developer.android.com/reference/javax/net/ssl/SSLSocket
      if (sdkVersion != null && sdkVersion >= 29) {
        assertThat(sslSocket.enabledProtocols)
          .containsExactly(
            TlsVersion.TLS_1_1.javaName,
            TlsVersion.TLS_1_2.javaName,
            TlsVersion.TLS_1_3.javaName,
          )
      } else if (sdkVersion != null && sdkVersion >= 26) {
        assertThat(sslSocket.enabledProtocols)
          .containsExactly(
            TlsVersion.TLS_1_1.javaName,
            TlsVersion.TLS_1_2.javaName,
          )
      } else {
        assertThat(sslSocket.enabledProtocols)
          .containsExactly(
            TlsVersion.SSL_3_0.javaName,
            TlsVersion.TLS_1_1.javaName,
            TlsVersion.TLS_1_2.javaName,
          )
      }
    } else {
      if (majorVersion > 11) {
        assertThat(sslSocket.enabledProtocols)
          .containsExactly(
            TlsVersion.SSL_3_0.javaName,
            TlsVersion.TLS_1_1.javaName,
            TlsVersion.TLS_1_2.javaName,
            TlsVersion.TLS_1_3.javaName,
          )
      } else {
        assertThat(sslSocket.enabledProtocols)
          .containsExactly(
            TlsVersion.SSL_3_0.javaName,
            TlsVersion.TLS_1_1.javaName,
            TlsVersion.TLS_1_2.javaName,
          )
      }
    }
  }

  @Test
  fun tls_missingTlsVersion() {
    platform.assumeNotConscrypt()
    platform.assumeNotBouncyCastle()
    val tlsSpec =
      ConnectionSpec.Builder(true)
        .cipherSuites(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
        .tlsVersions(TlsVersion.TLS_1_2)
        .supportsTlsExtensions(false)
        .build()
    val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
    socket.enabledCipherSuites =
      arrayOf(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
      )
    socket.enabledProtocols =
      arrayOf(
        TlsVersion.TLS_1_2.javaName,
        TlsVersion.TLS_1_1.javaName,
      )
    assertThat(tlsSpec.isCompatible(socket)).isTrue()
    socket.enabledProtocols = arrayOf(TlsVersion.TLS_1_1.javaName)
    assertThat(tlsSpec.isCompatible(socket)).isFalse()
  }

  @Test
  fun equalsAndHashCode() {
    val allCipherSuites =
      ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledCipherSuites()
        .build()
    val allTlsVersions =
      ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledTlsVersions()
        .build()
    val set: MutableSet<Any> = CopyOnWriteArraySet()
    assertThat(set.add(ConnectionSpec.MODERN_TLS)).isTrue()
    assertThat(set.add(ConnectionSpec.COMPATIBLE_TLS)).isTrue()
    assertThat(set.add(ConnectionSpec.CLEARTEXT)).isTrue()
    assertThat(set.add(allTlsVersions)).isTrue()
    assertThat(set.add(allCipherSuites)).isTrue()
    allCipherSuites.hashCode()
    assertThat(allCipherSuites.equals(null)).isFalse()
    assertThat(set.remove(ConnectionSpec.MODERN_TLS)).isTrue()
    assertThat(set.remove(ConnectionSpec.COMPATIBLE_TLS))
      .isTrue()
    assertThat(set.remove(ConnectionSpec.CLEARTEXT)).isTrue()
    assertThat(set.remove(allTlsVersions)).isTrue()
    assertThat(set.remove(allCipherSuites)).isTrue()
    assertThat(set).isEmpty()
    allTlsVersions.hashCode()
    assertThat(allTlsVersions.equals(null)).isFalse()
  }

  @Test
  fun allEnabledToString() {
    val connectionSpec =
      ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledTlsVersions()
        .allEnabledCipherSuites()
        .build()
    assertThat(connectionSpec.toString()).isEqualTo(
      "ConnectionSpec(cipherSuites=[all enabled], tlsVersions=[all enabled], " +
        "supportsTlsExtensions=true)",
    )
  }

  @Test
  fun simpleToString() {
    val connectionSpec =
      ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_2)
        .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        .build()
    assertThat(connectionSpec.toString()).isEqualTo(
      "ConnectionSpec(cipherSuites=[SSL_RSA_WITH_RC4_128_MD5], tlsVersions=[TLS_1_2], " +
        "supportsTlsExtensions=true)",
    )
  }
}
