package okhttp3

import java.net.Proxy as JavaProxy
import java.net.InetSocketAddress

sealed interface Proxy {
  val javaProxy: JavaProxy

  val socketAddress: InetSocketAddress?

  object Direct : Proxy {
    override val javaProxy: JavaProxy = JavaProxy.NO_PROXY

    override val socketAddress: InetSocketAddress?
      get() = null
  }

  data class Http(val server: HttpUrl) : Proxy {
    override val javaProxy: JavaProxy
      get() = JavaProxy(JavaProxy.Type.HTTP, socketAddress)

    override val socketAddress: InetSocketAddress
      get() = InetSocketAddress.createUnresolved(server.host, server.port)
  }

  data class Socks4(override val socketAddress: InetSocketAddress) : Proxy {
    override val javaProxy: JavaProxy
      get() = JavaProxy(JavaProxy.Type.SOCKS, socketAddress)
  }

  companion object {
    fun JavaProxy.toOkHttpProxy(): Proxy {
      return when (type()) {
        JavaProxy.Type.DIRECT -> {
          Direct
        }
        JavaProxy.Type.HTTP -> {
          // TODO is this safe
          val address = address() as InetSocketAddress
          Http(HttpUrl.Builder()
            .scheme("http")
                  .host(address.hostString)
                  .port(address.port)
            .build())
        }
        JavaProxy.Type.SOCKS -> {
          Socks4(address() as InetSocketAddress)
        }
      }
    }
  }
}

