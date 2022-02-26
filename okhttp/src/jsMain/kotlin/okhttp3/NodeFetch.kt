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

import kotlin.js.Promise
import kotlinx.browser.window

internal fun nodeFetchExecute(request: Request): Promise<Response> {
  return jsRequireNodeFetch()(request.url, request.toRequestInit()).then { it ->
    Response.Builder()
      .request(request)
      .code((it.status as Short).toInt())
      .message(it.statusText as String)
      .protocol(Protocol.HTTP_1_1)
      .build()
  } as Promise<Response>
}

// https://github.com/ktorio/ktor/blob/0081f943b434bdd0afd82424b389629e89a89461/ktor-client/ktor-client-core/js/src/io/ktor/client/engine/js/compatibility/Utils.kt
private fun jsRequireNodeFetch(): dynamic = try {
  js("eval('require')('node-fetch')")
} catch (cause: dynamic) {
  throw Error("Error loading module 'node-fetch': $cause")
}
