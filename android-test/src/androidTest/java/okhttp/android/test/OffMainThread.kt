/*
 * Copyright (C) 2025 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp.android.test

import android.os.Looper
import okhttp3.Call
import okhttp3.Response

/**
 * Sample of a Decorator that will fail any call on the Android Main thread.
 */
object OffMainThread : Interceptor {
  override fun newCall(chain: Call.Chain): Call = StrictModeCall(chain.proceed(chain.request))

  private class StrictModeCall(
    private val delegate: Call,
  ) : Call by delegate {
    override fun execute(): Response {
      if (Looper.getMainLooper() === Looper.myLooper()) {
        throw IllegalStateException("Network on main thread")
      }

      return delegate.execute()
    }

    override fun clone(): Call = StrictModeCall(delegate.clone())
  }
}
