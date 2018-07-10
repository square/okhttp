package okhttp3.kotlin

import kotlinx.coroutines.experimental.JobCancellationException
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

suspend fun OkHttpClient.execute(request: Request): Response {
  val call = this.newCall(request)
  return call.await()
}

suspend fun Call.await(): Response {
  return suspendCancellableCoroutine { cont ->
    cont.invokeOnCompletion(onCancelling = true) {
      if (!cont.isCompleted && it is JobCancellationException) {
          cancel()
      }
    }
    enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        if (!cont.isCancelled) {
          cont.resumeWithException(e)
        }
      }

      override fun onResponse(call: Call, response: Response) {
        if (!cont.isCancelled) {
          cont.resume(response)
        }
      }
    })
  }
}
