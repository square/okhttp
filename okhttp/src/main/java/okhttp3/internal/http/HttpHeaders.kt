/*
 * Copyright (C) 2012 The Android Open Source Project
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
@file:JvmName("HttpHeaders")

package okhttp3.internal.http

import okhttp3.Challenge
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Response
import okhttp3.internal.headersContentLength
import okhttp3.internal.http.StatusLine.Companion.HTTP_CONTINUE
import okhttp3.internal.platform.Platform
import okhttp3.internal.skipAll
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import java.io.EOFException
import java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import java.net.HttpURLConnection.HTTP_NO_CONTENT
import java.util.Collections

private val QUOTED_STRING_DELIMITERS = "\"\\".encodeUtf8()
private val TOKEN_DELIMITERS = "\t ,=".encodeUtf8()

/**
 * Parse RFC 7235 challenges. This is awkward because we need to look ahead to know how to
 * interpret a token.
 *
 * For example, the first line has a parameter name/value pair and the second line has a single
 * token68:
 *
 * ```
 * WWW-Authenticate: Digest foo=bar
 * WWW-Authenticate: Digest foo=
 * ```
 *
 * Similarly, the first line has one challenge and the second line has two challenges:
 *
 * ```
 * WWW-Authenticate: Digest ,foo=bar
 * WWW-Authenticate: Digest ,foo
 * ```
 */
fun Headers.parseChallenges(headerName: String): List<Challenge> {
  val result = mutableListOf<Challenge>()
  for (h in 0 until size) {
    if (headerName.equals(name(h), ignoreCase = true)) {
      val header = Buffer().writeUtf8(value(h))
      try {
        header.readChallengeHeader(result)
      } catch (e: EOFException) {
        Platform.get().log("Unable to parse challenge", Platform.WARN, e)
      }
    }
  }
  return result
}

@Throws(EOFException::class)
private fun Buffer.readChallengeHeader(result: MutableList<Challenge>) {
  var peek: String? = null

  while (true) {
    // Read a scheme name for this challenge if we don't have one already.
    if (peek == null) {
      skipCommasAndWhitespace()
      peek = readToken()
      if (peek == null) return
    }

    val schemeName = peek

    // Read a token68, a sequence of parameters, or nothing.
    val commaPrefixed = skipCommasAndWhitespace()
    peek = readToken()
    if (peek == null) {
      if (!exhausted()) return // Expected a token; got something else.
      result.add(Challenge(schemeName, emptyMap()))
      return
    }

    var eqCount = skipAll('='.toByte())
    val commaSuffixed = skipCommasAndWhitespace()

    // It's a token68 because there isn't a value after it.
    if (!commaPrefixed && (commaSuffixed || exhausted())) {
      result.add(Challenge(schemeName,
          Collections.singletonMap<String, String>(null, peek + "=".repeat(eqCount))))
      peek = null
      continue
    }

    // It's a series of parameter names and values.
    val parameters = mutableMapOf<String?, String>()
    eqCount += skipAll('='.toByte())
    while (true) {
      if (peek == null) {
        peek = readToken()
        if (skipCommasAndWhitespace()) break // We peeked a scheme name followed by ','.
        eqCount = skipAll('='.toByte())
      }
      if (eqCount == 0) break // We peeked a scheme name.
      if (eqCount > 1) return // Unexpected '=' characters.
      if (skipCommasAndWhitespace()) return // Unexpected ','.

      val parameterValue = when {
        startsWith('"'.toByte()) -> readQuotedString()
        else -> readToken()
      } ?: return // Expected a value.

      val replaced = parameters.put(peek, parameterValue)
      peek = null
      if (replaced != null) return // Unexpected duplicate parameter.
      if (!skipCommasAndWhitespace() && !exhausted()) return // Expected ',' or EOF.
    }
    result.add(Challenge(schemeName, parameters))
  }
}

/** Returns true if any commas were skipped. */
private fun Buffer.skipCommasAndWhitespace(): Boolean {
  var commaFound = false
  loop@ while (!exhausted()) {
    when (this[0]) {
      ','.toByte() -> {
        // Consume ','.
        readByte()
        commaFound = true
      }

      ' '.toByte(), '\t'.toByte() -> {
        readByte()
        // Consume space or tab.
      }

      else -> break@loop
    }
  }
  return commaFound
}

private fun Buffer.startsWith(prefix: Byte) = !exhausted() && this[0] == prefix

/**
 * Reads a double-quoted string, unescaping quoted pairs like `\"` to the 2nd character in each
 * sequence. Returns the unescaped string, or null if the buffer isn't prefixed with a
 * double-quoted string.
 */
@Throws(EOFException::class)
private fun Buffer.readQuotedString(): String? {
  require(readByte() == '\"'.toByte())
  val result = Buffer()
  while (true) {
    val i = indexOfElement(QUOTED_STRING_DELIMITERS)
    if (i == -1L) return null // Unterminated quoted string.

    if (this[i] == '"'.toByte()) {
      result.write(this, i)
      // Consume '"'.
      readByte()
      return result.readUtf8()
    }

    if (size == i + 1L) return null // Dangling escape.
    result.write(this, i)
    // Consume '\'.
    readByte()
    result.write(this, 1L) // The escaped character.
  }
}

/**
 * Consumes and returns a non-empty token, terminating at special characters in
 * [TOKEN_DELIMITERS]. Returns null if the buffer is empty or prefixed with a delimiter.
 */
private fun Buffer.readToken(): String? {
  var tokenSize = indexOfElement(TOKEN_DELIMITERS)
  if (tokenSize == -1L) tokenSize = size

  return when {
    tokenSize != 0L -> readUtf8(tokenSize)
    else -> null
  }
}

fun CookieJar.receiveHeaders(url: HttpUrl, headers: Headers) {
  if (this === CookieJar.NO_COOKIES) return

  val cookies = Cookie.parseAll(url, headers)
  if (cookies.isEmpty()) return

  saveFromResponse(url, cookies)
}

/**
 * Returns true if the response headers and status indicate that this response has a (possibly
 * 0-length) body. See RFC 7231.
 */
fun Response.promisesBody(): Boolean {
  // HEAD requests never yield a body regardless of the response headers.
  if (request.method == "HEAD") {
    return false
  }

  val responseCode = code
  if ((responseCode < HTTP_CONTINUE || responseCode >= 200) &&
      responseCode != HTTP_NO_CONTENT &&
      responseCode != HTTP_NOT_MODIFIED) {
    return true
  }

  // If the Content-Length or Transfer-Encoding headers disagree with the response code, the
  // response is malformed. For best compatibility, we honor the headers.
  if (headersContentLength() != -1L ||
      "chunked".equals(header("Transfer-Encoding"), ignoreCase = true)) {
    return true
  }

  return false
}

@Deprecated(
    message = "No longer supported",
    level = DeprecationLevel.ERROR,
    replaceWith = ReplaceWith(expression = "response.promisesBody()"))
fun hasBody(response: Response): Boolean {
  return response.promisesBody()
}
