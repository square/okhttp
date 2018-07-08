package okhttp3.kotlin

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
    return kotlinx.coroutines.experimental.suspendCancellableCoroutine { c ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                c.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                c.resume(response)
            }
        })
    }
}