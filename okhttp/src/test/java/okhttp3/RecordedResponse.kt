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
package okhttp3

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

/**
 * A received response or failure recorded by the response recorder.
 */
class RecordedResponse(
  @JvmField val request: Request,
  val response: Response?,
  val webSocket: WebSocket?,
  val body: String?,
  val failure: IOException?,
) {
  fun assertRequestUrl(url: HttpUrl) =
    apply {
      assertThat(request.url).isEqualTo(url)
    }

  fun assertRequestMethod(method: String) =
    apply {
      assertThat(request.method).isEqualTo(method)
    }

  fun assertRequestHeader(
    name: String,
    vararg values: String,
  ) = apply {
    assertThat(request.headers(name)).containsExactly(*values)
  }

  fun assertCode(expectedCode: Int) =
    apply {
      assertThat(response!!.code).isEqualTo(expectedCode)
    }

  fun assertSuccessful() =
    apply {
      assertThat(failure).isNull()
      assertThat(response!!.isSuccessful).isTrue()
    }

  fun assertNotSuccessful() =
    apply {
      assertThat(response!!.isSuccessful).isFalse()
    }

  fun assertHeader(
    name: String,
    vararg values: String?,
  ) = apply {
    assertThat(response!!.headers(name)).containsExactly(*values)
  }

  fun assertHeaders(headers: Headers) =
    apply {
      assertThat(response!!.headers).isEqualTo(headers)
    }

  fun assertBody(expectedBody: String) =
    apply {
      assertThat(body).isEqualTo(expectedBody)
    }

  fun assertHandshake() =
    apply {
      val handshake = response!!.handshake!!
      assertThat(handshake.tlsVersion).isNotNull()
      assertThat(handshake.cipherSuite).isNotNull()
      assertThat(handshake.peerPrincipal).isNotNull()
      assertThat(handshake.peerCertificates.size).isEqualTo(1)
      assertThat(handshake.localPrincipal).isNull()
      assertThat(handshake.localCertificates.size).isEqualTo(0)
    }

  /**
   * Asserts that the current response was redirected and returns the prior response.
   */
  fun priorResponse(): RecordedResponse {
    val priorResponse = response!!.priorResponse!!
    return RecordedResponse(priorResponse.request, priorResponse, null, null, null)
  }

  /**
   * Asserts that the current response used the network and returns the network response.
   */
  fun networkResponse(): RecordedResponse {
    val networkResponse = response!!.networkResponse!!
    return RecordedResponse(networkResponse.request, networkResponse, null, null, null)
  }

  /** Asserts that the current response didn't use the network.  */
  fun assertNoNetworkResponse() =
    apply {
      assertThat(response!!.networkResponse).isNull()
    }

  /** Asserts that the current response didn't use the cache.  */
  fun assertNoCacheResponse() =
    apply {
      assertThat(response!!.cacheResponse).isNull()
    }

  /**
   * Asserts that the current response used the cache and returns the cache response.
   */
  fun cacheResponse(): RecordedResponse {
    val cacheResponse = response!!.cacheResponse!!
    return RecordedResponse(cacheResponse.request, cacheResponse, null, null, null)
  }

  fun assertFailure(vararg allowedExceptionTypes: Class<*>) =
    apply {
      var found = false
      for (expectedClass in allowedExceptionTypes) {
        if (expectedClass.isInstance(failure)) {
          found = true
          break
        }
      }
      assertThat(
        found,
        "Expected exception type among ${allowedExceptionTypes.contentToString()}, got $failure",
      ).isTrue()
    }

  fun assertFailure(vararg messages: String) =
    apply {
      assertThat(failure, "No failure found").isNotNull()
      assertThat(messages).contains(failure!!.message)
    }

  fun assertFailureMatches(vararg patterns: String) =
    apply {
      val message = failure!!.message!!
      assertThat(
        patterns.firstOrNull { pattern ->
          message.matches(pattern.toRegex())
        },
      ).isNotNull()
    }

  fun assertSentRequestAtMillis(
    minimum: Long,
    maximum: Long,
  ) = apply {
    assertDateInRange(minimum, response!!.sentRequestAtMillis, maximum)
  }

  fun assertReceivedResponseAtMillis(
    minimum: Long,
    maximum: Long,
  ) = apply {
    assertDateInRange(minimum, response!!.receivedResponseAtMillis, maximum)
  }

  private fun assertDateInRange(
    minimum: Long,
    actual: Long,
    maximum: Long,
  ) {
    assertThat(actual, "${format(minimum)} <= ${format(actual)} <= ${format(maximum)}")
      .isBetween(minimum, maximum)
  }

  private fun format(time: Long) = SimpleDateFormat("HH:mm:ss.SSS").format(Date(time))
}
