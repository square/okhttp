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

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.testing.Flaky
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class OkPutHeadTest {
  @JvmField @Rule val clientTestRule = OkHttpClientTestRule()

  @Test
  @Throws(Exception::class)
  fun testHeadAfterPut() {
    val server = MockWebServer()
    server.enqueue(MockResponse().apply {
      setResponseCode(201)
      status = "HTTP/1.1 201 CREATED"
    })
    server.enqueue(MockResponse().apply {
      setResponseCode(204)
      status = "HTTP/1.1 204 NO CONTENT"
    })
    server.enqueue(MockResponse().apply {
      setResponseCode(204)
      status = "HTTP/1.1 204 NO CONTENT"
    })
    server.start()

    val client = clientTestRule.newClientBuilder().apply {
      callTimeout = 2000
    }.build()

    val endpointUrl = server.url("/endpoint")

    var request = Request.Builder()
        .url(endpointUrl)
        .header("Content-Type", "application/xml")
        .put(ValidRequestBody())
        .build()
    // 201
    client.newCall(request).execute()

    request = Request.Builder()
        .url(endpointUrl)
        .head()
        .build()
    // 204
    client.newCall(request).execute()

    request = Request.Builder()
        .url(endpointUrl)
        .header("Content-Type", "application/xml")
        .put(ErringRequestBody())
        .build()
    try {
      client.newCall(request).execute()
      fail("test should always throw exception")
    } catch (_: IOException) {
      // NOTE: expected
    }

    request = Request.Builder()
        .url(endpointUrl)
        .head()
        .build()

    client.newCall(request).execute()

    var recordedRequest = server.takeRequest()
    assertEquals("PUT", recordedRequest.method)

    recordedRequest = server.takeRequest()
    assertEquals("HEAD", recordedRequest.method)

    recordedRequest = server.takeRequest()
    assertEquals("HEAD", recordedRequest.method)

    server.shutdown()
  }

  internal class ErringRequestBody : RequestBody() {
    override fun contentType(): MediaType {
      return "application/xml".toMediaType()
    }

    override fun writeTo(sink: BufferedSink) {
      sink.writeUtf8("<el")
      sink.flush()
      throw IOException("failed to stream the XML")
    }
  }

  internal class ValidRequestBody : RequestBody() {
    override fun contentType(): MediaType {
      return "application/xml".toMediaType()
    }

    override fun writeTo(sink: BufferedSink) {
      sink.writeUtf8("<element/>")
      sink.flush()
    }
  }
}