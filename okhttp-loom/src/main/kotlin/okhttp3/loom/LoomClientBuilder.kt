package okhttp3.loom

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.internal.concurrent.TaskRunner

object LoomClientBuilder {
  fun clientBuilder(): OkHttpClient.Builder {
    val backend = LoomBackend()
    val taskRunner = TaskRunner(backend)

    return OkHttpClient.Builder()
      .dispatcher(Dispatcher(backend.executor))
      .connectionPool(
        ConnectionPool(
          maxIdleConnections = 5,
          keepAliveDuration = 5,
          timeUnit = TimeUnit.MINUTES,
          taskRunner = taskRunner
        )
      )
      .taskRunner(taskRunner)
  }
}
