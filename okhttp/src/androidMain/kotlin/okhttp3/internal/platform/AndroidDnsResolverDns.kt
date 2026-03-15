package okhttp3.internal.platform

import android.net.DnsResolver
import android.net.DnsResolver.Callback
import androidx.annotation.RequiresApi
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import okhttp3.Dns
import org.xbill.DNS.HTTPSRecord
import org.xbill.DNS.Message
import org.xbill.DNS.Section.ANSWER

@Suppress("NewApi")
@RequiresApi(37)
class AndroidDnsResolverDns : Dns {
  val dnsResolver = DnsResolver.getInstance()

  val httpsRecords: MutableMap<String, Future<HTTPSRecord?>> = HashMap()

  override fun lookup(hostname: String): List<InetAddress> {
    val future = CompletableFuture<HTTPSRecord?>()

    val callback: Callback<ByteArray> = object : Callback<ByteArray> {
      override fun onAnswer(p0: ByteArray, p1: Int) {
        val answers = Message(p0).getSection(ANSWER)
        if (answers.isEmpty()) {
          future.complete(null)
        } else {
          future.complete(answers.single() as HTTPSRecord)
        }
      }

      override fun onError(p0: DnsResolver.DnsException) {
        future.completeExceptionally(p0)
      }
    }
    @Suppress("WrongConstant")
    dnsResolver.rawQuery(
      null, hostname, DnsResolver.CLASS_IN, 65, DnsResolver.FLAG_EMPTY,
      { it.run() }, null,
      callback
    )
    httpsRecords[hostname] = future

    // TODO replace with DnsResolver call
    return Dns.SYSTEM.lookup(hostname)
  }
}
