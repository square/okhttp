/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */

package okhttp3

import okio.Buffer
import org.khronos.webgl.Uint8Array
import org.w3c.fetch.FOLLOW
import org.w3c.fetch.RequestInit
import org.w3c.fetch.RequestRedirect

internal fun Request.toRequestInit(): RequestInit {
  return (object: RequestInit {}).apply {
    method = this@toRequestInit.method
    headers = this@toRequestInit.headers.toJsHeaders()
    redirect = RequestRedirect.FOLLOW

    this@toRequestInit.body?.let {
      val buffer = Buffer().apply {
        it.writeTo(buffer)
      }
      body = buffer.readByteArray().unsafeCast<Uint8Array>()
    }
  }
}

private fun Headers.toJsHeaders(): dynamic {
  val jsHeaders = js("({})")
  this.forEach { (key, value) ->
    // TODO handle as multimap
    jsHeaders[key] = value
  }
  return jsHeaders
}

// https://github.com/ktorio/ktor/blob/0081f943b434bdd0afd82424b389629e89a89461/ktor-client/ktor-client-core/js/src/io/ktor/client/engine/js/JsUtils.kt
internal fun <T> buildObject(block: T.() -> Unit): T = (js("{}") as T).apply(block)
