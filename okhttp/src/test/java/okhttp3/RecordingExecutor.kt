package okhttp3

import org.assertj.core.api.Assertions.assertThat
import java.util.ArrayList
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

internal class RecordingExecutor(
  private val dispatcherTest: DispatcherTest
) : AbstractExecutorService() {
  private var shutdown: Boolean = false
  private val calls = ArrayList<RealCall.AsyncCall>()

  override fun execute(command: Runnable) {
    if (shutdown) throw RejectedExecutionException()
    calls.add(command as RealCall.AsyncCall)
  }

  fun assertJobs(vararg expectedUrls: String) {
    val actualUrls = calls.map { it.request().url().toString() }
    assertThat(actualUrls).containsExactly(*expectedUrls)
  }

  fun finishJob(url: String) {
    val i = calls.iterator()
    while (i.hasNext()) {
      val call = i.next()
      if (call.request().url().toString() == url) {
        i.remove()
        dispatcherTest.dispatcher.finished(call)
        return
      }
    }
    throw AssertionError("No such job: $url")
  }

  override fun shutdown() {
    shutdown = true
  }

  override fun shutdownNow(): List<Runnable> {
    throw UnsupportedOperationException()
  }

  override fun isShutdown(): Boolean {
    throw UnsupportedOperationException()
  }

  override fun isTerminated(): Boolean {
    throw UnsupportedOperationException()
  }

  override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
    throw UnsupportedOperationException()
  }
}
