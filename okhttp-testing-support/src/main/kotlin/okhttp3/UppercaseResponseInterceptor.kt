/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3

import java.io.IOException
import okhttp3.Interceptor.Chain
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** Rewrites the response body returned from the server to be all uppercase.  */
class UppercaseResponseInterceptor : Interceptor {
  @Throws(IOException::class)
  override fun intercept(chain: Chain): Response {
    return uppercaseResponse(chain.proceed(chain.request()))
  }

  private fun uppercaseResponse(response: Response): Response {
    val uppercaseBody: ResponseBody =
      object : ForwardingResponseBody(response.body) {
        override fun source(): BufferedSource {
          return uppercaseSource(delegate().source()).buffer()
        }
      }
    return response.newBuilder()
        .body(uppercaseBody)
        .build()
  }

  private fun uppercaseSource(source: BufferedSource): ForwardingSource {
    return object : ForwardingSource(source) {
      @Throws(IOException::class)
      override fun read(
        sink: Buffer,
        byteCount: Long
      ): Long {
        val buffer = Buffer()
        val read = delegate.read(buffer, byteCount)
        if (read != -1L) {
          sink.write(buffer.readByteString().toAsciiUppercase())
        }
        return read
      }
    }
  }
}
