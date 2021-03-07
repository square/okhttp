/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3.internal.http

import okhttp3.HttpUrl
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.Proxy

object RequestLine {

  /**
   * Returns the request status line, like "GET / HTTP/1.1". This is exposed to the application by
   * [HttpURLConnection.getHeaderFields], so it needs to be set even if the transport is
   * HTTP/2.
   */
  fun get(request: Request, proxyType: Proxy.Type) = buildString {
    append(request.method)
    append(' ')
    if (includeAuthorityInRequestLine(request, proxyType)) {
      append(request.url)
    } else {
      append(requestPath(request.url))
    }
    append(" HTTP/1.1")
  }

  /**
   * Returns true if the request line should contain the full URL with host and port (like "GET
   * http://android.com/foo HTTP/1.1") or only the path (like "GET /foo HTTP/1.1").
   */
  private fun includeAuthorityInRequestLine(request: Request, proxyType: Proxy.Type): Boolean {
    return !request.isHttps && proxyType == Proxy.Type.HTTP
  }

  /**
   * Returns the path to request, like the '/' in 'GET / HTTP/1.1'. Never empty, even if the request
   * URL is. Includes the query component if it exists.
   */
  fun requestPath(url: HttpUrl): String {
    val path = url.encodedPath
    val query = url.encodedQuery
    return if (query != null) "$path?$query" else path
  }
}
