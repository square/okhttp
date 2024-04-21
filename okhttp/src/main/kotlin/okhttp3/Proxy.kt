package okhttp3

import java.net.InetSocketAddress
import java.net.Proxy as JavaProxy

sealed interface Proxy {
  val javaProxy: JavaProxy

  val socketAddress: InetSocketAddress?

  object Direct : Proxy {
    override val javaProxy: JavaProxy = JavaProxy.NO_PROXY

    override val socketAddress: InetSocketAddress?
      get() = null

    override fun toString(): String {
      return "Direct"
    }
  }

  data class Http(val server: HttpUrl) : Proxy {
    override val javaProxy: JavaProxy = JavaProxy(JavaProxy.Type.HTTP, socketAddress)

    override val socketAddress: InetSocketAddress
      get() = InetSocketAddress.createUnresolved(server.host, server.port)

    companion object {
      fun insecure(address: InetSocketAddress): Http {
        return Http(
          HttpUrl.Builder()
            .scheme("http")
            .host(address.hostString)
            .port(address.port)
            .build(),
        )
      }
    }
  }

  data class Socks4(override val socketAddress: InetSocketAddress) : Proxy {
    override val javaProxy: JavaProxy = JavaProxy(JavaProxy.Type.SOCKS, socketAddress)
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
          Http(
            HttpUrl.Builder()
              .scheme("http")
              .host(address.hostString)
              .port(address.port)
              .build(),
          )
        }
        JavaProxy.Type.SOCKS -> {
          Socks4(address() as InetSocketAddress)
        }
      }
    }
  }
}
