package okhttp3

import java.io.IOException
import java.security.Security
import okhttp3.internal.platform.Platform
import org.conscrypt.Conscrypt

// TLS 1.3
private val TLS13_CIPHER_SUITES =
  listOf(
    CipherSuite.TLS_AES_128_GCM_SHA256,
    CipherSuite.TLS_AES_256_GCM_SHA384,
    CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
    CipherSuite.TLS_AES_128_CCM_SHA256,
    CipherSuite.TLS_AES_128_CCM_8_SHA256,
  )

/**
 * A TLS 1.3 only Connection Spec. This will be eventually be exposed
 * as part of MODERN_TLS or folded into the default OkHttp client once published and
 * available in JDK11 or Conscrypt.
 */
private val TLS_13 =
  ConnectionSpec.Builder(true)
    .cipherSuites(*TLS13_CIPHER_SUITES.toTypedArray())
    .tlsVersions(TlsVersion.TLS_1_3)
    .build()

private val TLS_12 =
  ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
    .tlsVersions(TlsVersion.TLS_1_2)
    .build()

private fun testClient(
  urls: List<String>,
  client: OkHttpClient,
) {
  try {
    for (url in urls) {
      sendRequest(client, url)
    }
  } finally {
    client.dispatcher.executorService.shutdownNow()
    client.connectionPool.evictAll()
  }
}

private fun buildClient(vararg specs: ConnectionSpec): OkHttpClient {
  return OkHttpClient.Builder()
    .connectionSpecs(listOf(*specs))
    .build()
}

private fun sendRequest(
  client: OkHttpClient,
  url: String,
) {
  System.out.printf("%-40s ", url)
  System.out.flush()
  println(Platform.get())
  val request =
    Request.Builder()
      .url(url)
      .build()
  try {
    client.newCall(request).execute().use { response ->
      val handshake = response.handshake
      println(
        "${handshake!!.tlsVersion} ${handshake.cipherSuite} ${response.protocol} " +
          "${response.code} ${response.body.bytes().size}b",
      )
    }
  } catch (ioe: IOException) {
    println(ioe)
  }
}

fun main(vararg args: String) {
  // System.setProperty("javax.net.debug", "ssl:handshake:verbose");
  Security.insertProviderAt(Conscrypt.newProviderBuilder().provideTrustManager().build(), 1)
  println("Running tests using ${Platform.get()} ${System.getProperty("java.vm.version")}")

  // https://github.com/tlswg/tls13-spec/wiki/Implementations
  val urls =
    listOf(
      "https://enabled.tls13.com",
      "https://www.howsmyssl.com/a/check",
      "https://tls13.cloudflare.com",
      "https://www.allizom.org/robots.txt",
      "https://tls13.crypto.mozilla.org/",
      "https://tls.ctf.network/robots.txt",
      "https://rustls.jbp.io/",
      "https://h2o.examp1e.net",
      "https://mew.org/",
      "https://tls13.baishancloud.com/",
      "https://tls13.akamai.io/",
      "https://swifttls.org/",
      "https://www.googleapis.com/robots.txt",
      "https://graph.facebook.com/robots.txt",
      "https://api.twitter.com/robots.txt",
      "https://connect.squareup.com/robots.txt",
    )

  println("TLS1.3+TLS1.2")
  testClient(urls, buildClient(ConnectionSpec.RESTRICTED_TLS))

  println("\nTLS1.3 only")
  testClient(urls, buildClient(TLS_13))

  println("\nTLS1.3 then fallback")
  testClient(urls, buildClient(TLS_13, TLS_12))
}
