package okhttp3.internal.socket

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import okhttp3.internal.peerName
import okio.BufferedSink
import okio.BufferedSource
import okio.Closeable
import okio.buffer
import okio.sink
import okio.source

interface OkioSocketFactory {
  fun newServerSocket(): OkioServerSocket
}

interface OkioSslSocketFactory {
  fun createSocket(
    socket: OkioSocket,
  ): OkioSocket
}

interface OkioSslSocket : OkioSocket {
  fun startHandshake()
}

class RealOkioSslSocketFactory(
  val delegate: SSLSocketFactory,
) : OkioSslSocketFactory {
  override fun createSocket(socket: OkioSocket): OkioSocket {
    val delegateSocket = (socket as RealOkioSocket).delegate
    return RealOkioSocket(
      delegate.createSocket(
        delegateSocket,
        delegateSocket.inetAddress.hostAddress,
        delegateSocket.port,
        true
      )
    )
  }
}

class RealOkioSocketFactory : OkioSocketFactory {
  override fun newServerSocket(): OkioServerSocket {
    val serverSocket = ServerSocket()
    serverSocket.reuseAddress = false
    serverSocket.bind(InetSocketAddress("localhost", 0), 1)
    return RealOkioServerSocket(serverSocket)
  }
}

interface OkioSocket : Closeable {
  val source: BufferedSource
  val sink: BufferedSink

  val peerName: String
}

interface OkioServerSocket : Closeable {
  fun accept(): OkioSocket
  val localPort: Int
}

class RealOkioSocket(
  val delegate: Socket,
) : OkioSocket {
  override val source: BufferedSource = delegate.getInputStream().source().buffer()
  override val sink: BufferedSink = delegate.getOutputStream().sink().buffer()

  override fun close() {
    delegate.use {
      sink.flush()
    }
  }

  override val peerName: String
    get() = delegate.peerName()
}

class RealOkioServerSocket(
  private val delegate: ServerSocket,
) : OkioServerSocket {
  override fun accept(): OkioSocket {
    return RealOkioSocket(delegate.accept())
  }

  override val localPort: Int
    get() = delegate.localPort

  override fun close() {
    delegate.close()
  }
}
