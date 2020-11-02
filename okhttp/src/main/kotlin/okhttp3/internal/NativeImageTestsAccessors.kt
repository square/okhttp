package okhttp3.internal

import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.Response
import okhttp3.internal.connection.Exchange
import okhttp3.internal.connection.RealCall
import okhttp3.internal.connection.RealConnection
import okhttp3.internal.io.FileSystem
import java.io.File

fun buildCache(file: File, maxSize: Long, fileSystem: FileSystem): Cache {
  return Cache(file, maxSize, fileSystem)
}

val IDLE_CONNECTION_HEALTHY_NS = okhttp3.internal.connection.RealConnection.Companion.IDLE_CONNECTION_HEALTHY_NS

var RealConnection.idleAtNsAccessor
  get() = idleAtNs
  set(value) {
    idleAtNs = value
  }

val Response.exchange
  get() = this.exchange

val Exchange.connection
  get() = this.connection

fun Dispatcher.finished(call: RealCall.AsyncCall) = this.finished(call)