package okhttp3.internal.socket

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.LinkedBlockingQueue
import okhttp3.internal.concurrent.TaskFaker
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer

class TaskFakerOkioServerSocketFactory(
  val taskFaker: TaskFaker,
) : OkioServerSocketFactory {
  var nextServerSocketPort = 1024
  val serverSockets = mutableMapOf<Int, TaskFakerOkioServerSocket>()

  override fun newServerSocket(): OkioServerSocket {
    val port = nextServerSocketPort++
    val result = TaskFakerOkioServerSocket(taskFaker, port)
    serverSockets[port] = result
    return result
  }
}

class TaskFakerOkioServerSocket(
  private val taskFaker: TaskFaker,
  override val localPort: Int,
) : OkioServerSocket {
  private val sockets =
    taskFaker.taskRunner.backend.decorate(LinkedBlockingQueue<TaskFakerOkioSocket>())
  override var reuseAddress = false
  override val localSocketAddress: SocketAddress?
    get() = TODO("Not yet implemented")

  override fun accept(): OkioSocket {
    return sockets.poll()
  }

  override fun bind(
    socketAddress: SocketAddress,
    port: Int,
  ) {
  }

  override fun close() {
  }
}

/** Like Pipe, with no concurrency. */
class Stream(
  val taskFaker: TaskFaker,
) {
  val buffer = Buffer()
  var sourceClosed = false
  var sinkClosed = false

  val source =
    object : Source {
      override fun read(
        sink: Buffer,
        byteCount: Long,
      ): Long {
        require(!sourceClosed)
        taskFaker.yieldUntil { sinkClosed || !buffer.exhausted() }
        return buffer.read(sink, byteCount)
      }

      override fun timeout() = Timeout.NONE // TODO

      override fun close() {
        sourceClosed = true
      }
    }

  val sink =
    object : Sink {
      override fun timeout() = Timeout.NONE // TODO

      override fun write(
        source: Buffer,
        byteCount: Long,
      ) {
        require(!sinkClosed)
        return buffer.write(source, byteCount)
      }

      override fun flush() {
      }

      override fun close() {
        sinkClosed = true
      }
    }
}

class SocketPair(
  taskFaker: TaskFaker,
  clientAddress: InetSocketAddress,
  serverAddress: InetSocketAddress,
) {
  private val clientToServer = Stream(taskFaker)
  private val serverToClient = Stream(taskFaker)

  val clientSocket =
    TaskFakerOkioSocket(
      serverToClient.source.buffer(),
      clientToServer.sink.buffer(),
      clientAddress,
      serverAddress,
    )

  val serverSocket =
    TaskFakerOkioSocket(
      clientToServer.source.buffer(),
      serverToClient.sink.buffer(),
      serverAddress,
      clientAddress,
    )
}

class TaskFakerOkioSocket(
  override val source: BufferedSource,
  override val sink: BufferedSink,
  private val localSocketAddress: InetSocketAddress,
  override val remoteSocketAddress: InetSocketAddress,
) : OkioSocket {
  override var soTimeout: Int
    get() = TODO("Not yet implemented")
    set(value) {}
  override val isClosed: Boolean
    get() = TODO("Not yet implemented")
  override val isInputShutdown: Boolean
    get() = TODO("Not yet implemented")
  override val isOutputShutdown: Boolean
    get() = TODO("Not yet implemented")
  override val peerName: String
    get() = remoteSocketAddress.address.hostName
  override val localPort: Int
    get() = localSocketAddress.port
  override val localAddress: InetAddress
    get() = localSocketAddress.address

  override fun connect(address: InetSocketAddress) {
    TODO("Not yet implemented")
  }

  override fun connect(
    address: InetSocketAddress,
    connectTimeout: Int,
  ) {
    TODO("Not yet implemented")
  }

  override val inetAddress: InetAddress?
    get() = remoteSocketAddress.address

  override fun shutdownOutput() {
  }

  override fun shutdownInput() {
  }

  override fun close() {
    sink.use {
      source.close()
    }
  }
}
