package okhttp3.internal.http

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okio.IOException

class InterceptorCallFactory(val delegate: Call.Factory) : Call.Factory {
  override fun newCall(request: Request): Call {
   return InterceptorCall(delegate.newCall(request))
  }
}

class InterceptorCall(val delegate: Call): Call by delegate  {

  override fun enqueue(responseCallback: Callback) {
    try {
      responseCallback.onResponse(this, delegate.execute())
    } catch (ioe: IOException) {
      responseCallback.onFailure(this, ioe)
    }
  }

  override fun clone(): Call = InterceptorCall(delegate.clone())
}
