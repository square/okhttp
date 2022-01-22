/*
 * Copyright (C) 2022 Square, Inc.
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

@Suppress("unused")
fun HttpMethod.commonInvalidatesCache(method: String): Boolean = (method == "POST" ||
  method == "PATCH" ||
  method == "PUT" ||
  method == "DELETE" ||
  method == "MOVE") // WebDAV

@Suppress("unused")
fun HttpMethod.commonRequiresRequestBody(method: String): Boolean = (method == "POST" ||
  method == "PUT" ||
  method == "PATCH" ||
  method == "PROPPATCH" || // WebDAV
  method == "REPORT") // CalDAV/CardDAV (defined in WebDAV Versioning)

@Suppress("unused")
fun HttpMethod.commonPermitsRequestBody(method: String): Boolean = !(method == "GET" || method == "HEAD")

@Suppress("unused")
fun HttpMethod.commonRedirectsWithBody(method: String): Boolean =
  // (WebDAV) redirects should also maintain the request body
  method == "PROPFIND"

@Suppress("unused")
fun HttpMethod.commonRedirectsToGet(method: String): Boolean =
  // All requests but PROPFIND should redirect to a GET request.
  method != "PROPFIND"
