/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.recipes.kt

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.decodeCertificatePem
import okhttp3.tls.internal.TlsUtil
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager

class ClientAuth {
  val request = Request.Builder()
    .url("https://server.cryptomix.com/secure/")
    .build()

  val localhost = TlsUtil.localhostCerts()

  fun run() {
    println("Sending request using platform keys and certificates")
    sendNormalRequest()

    println("\nSending request using custom key manager and site certificate")
    sendCustomRequest()
  }

  private fun sendCustomRequest() {
    val cert = """
      -----BEGIN CERTIFICATE-----
      MIIDSjCCAjKgAwIBAgIQRK+wgNajJ7qJMDmGLvhAazANBgkqhkiG9w0BAQUFADA/
      MSQwIgYDVQQKExtEaWdpdGFsIFNpZ25hdHVyZSBUcnVzdCBDby4xFzAVBgNVBAMT
      DkRTVCBSb290IENBIFgzMB4XDTAwMDkzMDIxMTIxOVoXDTIxMDkzMDE0MDExNVow
      PzEkMCIGA1UEChMbRGlnaXRhbCBTaWduYXR1cmUgVHJ1c3QgQ28uMRcwFQYDVQQD
      Ew5EU1QgUm9vdCBDQSBYMzCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEB
      AN+v6ZdQCINXtMxiZfaQguzH0yxrMMpb7NnDfcdAwRgUi+DoM3ZJKuM/IUmTrE4O
      rz5Iy2Xu/NMhD2XSKtkyj4zl93ewEnu1lcCJo6m67XMuegwGMoOifooUMM0RoOEq
      OLl5CjH9UL2AZd+3UWODyOKIYepLYYHsUmu5ouJLGiifSKOeDNoJjj4XLh7dIN9b
      xiqKqy69cK3FCxolkHRyxXtqqzTWMIn/5WgTe1QLyNau7Fqckh49ZLOMxt+/yUFw
      7BZy1SbsOFU5Q9D8/RhcQPGX69Wam40dutolucbY38EVAjqr2m7xPi71XAicPNaD
      aeQQmxkqtilX4+U9m5/wAl0CAwEAAaNCMEAwDwYDVR0TAQH/BAUwAwEB/zAOBgNV
      HQ8BAf8EBAMCAQYwHQYDVR0OBBYEFMSnsaR7LHH62+FLkHX/xBVghYkQMA0GCSqG
      SIb3DQEBBQUAA4IBAQCjGiybFwBcqR7uKGY3Or+Dxz9LwwmglSBd49lZRNI+DT69
      ikugdB/OEIKcdBodfpga3csTS7MgROSR6cz8faXbauX+5v3gTt23ADq1cEmv8uXr
      AvHRAosZy5Q6XkjEGB5YGV8eAlrwDPGxrancWYaLbumR9YbK+rlmM6pZW87ipxZz
      R8srzJmwN0jP41ZL9c8PDHIyh8bwRLtTcm1D9SZImlJnt1ir/md2cXjbDaJWFBM5
      JDGFoqgCWjBH4d1QB7wCCZAA62RjYJsWvIjJEubSfZGL+T0yjWW06XyxV3bqxbYo
      Ob8VZRzI9neWagqNdwvYkQsEjgfbKbYK7p2CNTUQ
      -----END CERTIFICATE-----
    """.trimIndent()

    val m = HandshakeCertificates.Builder()
      .addTrustedCertificate(cert.decodeCertificatePem())
      .build()

    val keyManager = FixedKeyManager(localhost.keyPair.private, localhost.certificate)

    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(arrayOf(keyManager), arrayOf(m.trustManager), null)

    val client = OkHttpClient.Builder()
      .sslSocketFactory(sslContext.socketFactory, m.trustManager)
      .build()

    sendRequest(client)
  }

  private fun sendNormalRequest() {
    val m = HandshakeCertificates.Builder()
      .addPlatformTrustedCertificates()
      .heldCertificate(localhost)
      .build()

    val client = OkHttpClient.Builder()
      .sslSocketFactory(m.sslSocketFactory(), m.trustManager)
      .build()

    sendRequest(client)
  }

  private fun sendRequest(client: OkHttpClient) {
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Unexpected code $response")

      val lines = response.body!!.string().lines().filter { it.contains("SSL_CLIENT") }
      println(lines.joinToString("\n"))
    }
  }
}

class FixedKeyManager(val pk: PrivateKey, vararg val chain: X509Certificate) : X509KeyManager {
  override fun getClientAliases(keyType: String?, issuers: Array<Principal>): Array<String> {
    return arrayOf("mykey")
  }

  override fun chooseClientAlias(
    keyType: Array<out String>?,
    issuers: Array<out Principal>?,
    socket: Socket?
  ): String? {
    println("Key Type: ${keyType?.toList()}")
    println("Issuers: ${issuers?.map { it.name }}")
    println("Address: ${socket?.inetAddress?.hostName}")

    return if (socket?.inetAddress?.hostName == "server.cryptomix.com") {
      "mykey"
    } else {
      null
    }
  }

  override fun getCertificateChain(alias: String?): Array<out X509Certificate> = chain

  override fun getPrivateKey(alias: String?): PrivateKey = pk

  override fun getServerAliases(keyType: String?, issuers: Array<Principal>): Array<String> = TODO()

  override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>, socket: Socket): String = TODO()
}

fun main() {
  ClientAuth().run()
}
