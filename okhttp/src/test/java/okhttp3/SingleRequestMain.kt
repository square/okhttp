package okhttp3

import okhttp3.internal.platform.Platform
import okio.ByteString.Companion.toByteString
import org.conscrypt.Conscrypt
import java.security.Security
import javax.net.ssl.SSLSocket

fun main() {
  System.setProperty("javax.net.debug", "ssl:handshake:session:sessioncache")

  val conscrypt = false

  val provider = Conscrypt.newProviderBuilder().provideTrustManager(true).build()
  if (conscrypt) {
    Security.insertProviderAt(provider, 1)
  } else {
//    System.setProperty("jdk.tls.client.enableSessionTicketExtension", "true")
  }

  val client = OkHttpClient.Builder().apply {

  }.build()

  println(Platform.get())
  println("Session Cache Size ${client.sslSessionContext?.sessionCacheSize}")
  println("Session Timeout ${client.sslSessionContext?.sessionTimeout}")
  println(
      "Session Ids ${client.sslSessionContext?.ids?.toList()?.map { it.toByteString().hex() }}")

  try {
    val request = Request.Builder().url("https://facebook.com/robots.txt").build()

    executeCall(client, request)

    println()

    println(
        "Session Ids ${client.sslSessionContext?.ids?.toList()?.map { it.toByteString().hex() }}")

    client.connectionPool.evictAll()
    println("Connection Count ${client.connectionPool.connectionCount()}")

    executeCall(client, request)

    println(
        "Session Ids ${client.sslSessionContext?.ids?.toList()?.map { it.toByteString().hex() }}")

    println()
  } finally {
    client.connectionPool.evictAll()
    client.dispatcher.executorService.shutdownNow()
  }
}

private fun executeCall(client: OkHttpClient, request: Request) {
  println()

  val call = client.newCall(request)

  call.execute().use { response ->
    println("Protocol ${response.protocol}")
    println("Response Code ${response.code}")
    val socket = response.exchange?.connection()?.socket() as SSLSocket
    println("Request Session Context ${socket.session.sessionContext}")
    println("Request Session Id ${socket.session.id.toByteString().hex()}")
    println("Session Creation Time ${socket.session.creationTime}")
    println("Session Valid ${socket.session.isValid}")
  }
}