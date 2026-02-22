package mockwebserver.socket

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

public class RecordingSocketFactory(
  private val socketEventListener: SocketEventListener,
  private val delegate: SocketFactory = getDefault(),
) : SocketFactory() {
  override fun createSocket(): Socket = RecordingSocket(delegate.createSocket(), socketEventListener)

  override fun createSocket(
    host: String?,
    port: Int,
  ): Socket = RecordingSocket(delegate.createSocket(host, port), socketEventListener)

  override fun createSocket(
    host: String?,
    port: Int,
    localHost: InetAddress?,
    localPort: Int,
  ): Socket =
    RecordingSocket(
      delegate.createSocket(host, port, localHost, localPort),
      socketEventListener,
    )

  override fun createSocket(
    host: InetAddress?,
    port: Int,
  ): Socket = RecordingSocket(delegate.createSocket(host, port), socketEventListener)

  override fun createSocket(
    address: InetAddress?,
    port: Int,
    localAddress: InetAddress?,
    localPort: Int,
  ): Socket =
    RecordingSocket(
      delegate.createSocket(address, port, localAddress, localPort),
      socketEventListener,
    )
}
