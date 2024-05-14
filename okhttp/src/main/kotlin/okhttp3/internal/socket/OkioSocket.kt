package okhttp3.internal.socket

import kotlin.Any as Any1
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import javax.net.ServerSocketFactory
import javax.net.SocketFactory
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import okhttp3.internal.peerName
import okio.BufferedSink
import okio.BufferedSource
import okio.Closeable
import okio.buffer
import okio.sink
import okio.source

interface OkioSocketFactory {
  fun createSocket(): OkioSocket
  fun createSocket(proxy: Proxy): OkioSocket
  fun createSocket(host: String, port: Int): OkioSocket
}

class RealOkioSocketFactory(
  internal val delegate: SocketFactory = SocketFactory.getDefault(),
) : OkioSocketFactory {
  override fun createSocket() = RealOkioSocket(delegate.createSocket() ?: Socket())
  override fun createSocket(host: String, port: Int): OkioSocket =
    RealOkioSocket(delegate.createSocket(host, port) ?: Socket(host, port))

  override fun createSocket(proxy: Proxy) = RealOkioSocket(Socket(proxy)) // Don't delegate.
}

interface OkioServerSocketFactory {
  fun newServerSocket(): OkioServerSocket
}

interface OkioSslSocketFactory {
  fun createSocket(socket: OkioSocket): OkioSslSocket
  fun createSocket(socket: OkioSocket, host: String, port: Int): OkioSslSocket
}

interface OkioSslSocket : OkioSocket {
  val session: SSLSession?
  val enabledProtocols: Array<String>

  fun startHandshake()
}

class RealOkioSslSocket(internal val delegate: SSLSocket) :
  OkioSslSocket, OkioSocket by RealOkioSocket(delegate) {
  override val session: SSLSession? get() = delegate.session
  override val enabledProtocols: Array<String> get() = delegate.enabledProtocols

  override fun startHandshake() {
    delegate.startHandshake()
  }
}

class RealOkioSslSocketFactory(
  val delegate: SSLSocketFactory,
) : OkioSslSocketFactory {
  override fun createSocket(socket: OkioSocket): OkioSslSocket {
    val delegateSocket = (socket as RealOkioSocket).delegate
    return createSocket(
      socket,
      delegateSocket.inetAddress.hostAddress,
      delegateSocket.port,
    )
  }

  override fun createSocket(socket: OkioSocket, host: String, port: Int): OkioSslSocket {
    val delegateSocket = (socket as RealOkioSocket).delegate
    val sslSocket = delegate.createSocket(
      delegateSocket,
      host,
      port,
      true
    ) as SSLSocket
    return RealOkioSslSocket(sslSocket)
  }
}

class RealOkioServerSocketFactory(
  private val delegate: ServerSocketFactory = ServerSocketFactory.getDefault(),
) : OkioServerSocketFactory {
  override fun newServerSocket(): OkioServerSocket {
    val serverSocket = delegate.createServerSocket()
    return RealOkioServerSocket(serverSocket)
  }
}

interface OkioSocket : Closeable {
  val source: BufferedSource
  val sink: BufferedSink

  var soTimeout: Int
  val isClosed: Boolean
  val isInputShutdown: Boolean
  val isOutputShutdown: Boolean
  val peerName: String
  val localPort: Int
  val inetAddress: InetAddress?
  val localAddress: InetAddress
  val remoteSocketAddress: SocketAddress?

  fun connect(address: InetSocketAddress)
  fun connect(address: InetSocketAddress, connectTimeout: Int)

  fun shutdownOutput()
  fun shutdownInput()
}

interface OkioServerSocket : Closeable {
  val localPort: Int
  var reuseAddress: Boolean
  fun accept(): OkioSocket
  fun bind(socketAddress: SocketAddress, port: Int)
  val localSocketAddress: SocketAddress?
}

class RealOkioSocket(
  val delegate: Socket,
) : OkioSocket {
  private var source_: BufferedSource? = null
  private var sink_: BufferedSink? = null

  override val source: BufferedSource get() = source_
    ?: delegate.source().buffer().also { source_ = it }
  override val sink: BufferedSink get() = sink_
    ?: delegate.sink().buffer().also { sink_ = it }

  override val localPort: Int by delegate::localPort
  override val inetAddress: InetAddress? by delegate::inetAddress
  override val localAddress: InetAddress by delegate::localAddress
  override val remoteSocketAddress: SocketAddress? by delegate::remoteSocketAddress
  override var soTimeout: Int by delegate::soTimeout

  override val peerName: String get() = delegate.peerName()
  override val isClosed: Boolean get() = delegate.isClosed
  override val isInputShutdown: Boolean get() = delegate.isInputShutdown
  override val isOutputShutdown: Boolean get() = delegate.isOutputShutdown

  override fun connect(address: InetSocketAddress) {
    delegate.connect(address)
  }

  override fun connect(address: InetSocketAddress, connectTimeout: Int) {
    delegate.connect(address, connectTimeout)
  }

  override fun close() {
    // Note that this potentially leaves bytes in sink. This is necessary because Socket.close() is
    // much more like cancel() (asynchronously interrupt) than close() (release resources).
    delegate.close()
  }

  override fun shutdownOutput() {
    delegate.shutdownOutput()
  }

  override fun shutdownInput() {
    delegate.shutdownInput()
  }
}

class RealOkioServerSocket(
  private val delegate: ServerSocket,
) : OkioServerSocket {
  override val localPort by delegate::localPort
  override var reuseAddress by delegate::reuseAddress
  override val localSocketAddress: InetSocketAddress? get() = delegate.localSocketAddress as? InetSocketAddress

  override fun accept(): OkioSocket {
    return RealOkioSocket(delegate.accept())
  }

  override fun bind(socketAddress: SocketAddress, port: Int) {
    delegate.bind(socketAddress, port)
  }

  override fun close() {
    delegate.close()
  }
}
