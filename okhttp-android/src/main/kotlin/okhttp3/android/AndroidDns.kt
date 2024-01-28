package okhttp3.android

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

object AndroidDns: Dns {
  private val dns: Dns = Dns.SYSTEM

  val clearDnsCache = kotlin.runCatching { InetAddress::class.java.getMethod("clearDnsCache") }

  override fun lookup(hostname: String): List<InetAddress> {
    return try {
      dns.lookup(hostname)
    } catch (uhe: UnknownHostException) {
      if (uhe.cause != null || clearDnsCache.isFailure) {
        // Result was a real network call
        throw uhe
      } else {
        // Result was cached by AddressCache for 2 seconds, force it through
        // https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/main/java/java/net/Inet6AddressImpl.java
        // https://android.googlesource.com/platform/libcore/+/refs/heads/main/luni/src/main/java/java/net/AddressCache.java
        clearDnsCache.getOrThrow().invoke(null)

        dns.lookup(hostname)
      }
    }
  }
}
