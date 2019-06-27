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

/** Exposes Kotlin-internal APIs to Java test code and code in other modules. */
@file:JvmName("Internal")

package okhttp3.internal

import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import javax.net.ssl.SSLSocket

fun parseCookie(currentTimeMillis: Long, url: HttpUrl, setCookie: String): Cookie? =
    Cookie.parse(currentTimeMillis, url, setCookie)

fun cookieToString(cookie: Cookie, forObsoleteRfc2965: Boolean) =
    cookie.toString(forObsoleteRfc2965)

fun addHeaderLenient(builder: Headers.Builder, line: String) =
    builder.addLenient(line)

fun addHeaderLenient(builder: Headers.Builder, name: String, value: String) =
    builder.addLenient(name, value)

fun cacheGet(cache: Cache, request: Request) = cache.get(request)

fun applyConnectionSpec(connectionSpec: ConnectionSpec, sslSocket: SSLSocket, isFallback: Boolean) =
    connectionSpec.apply(sslSocket, isFallback)
