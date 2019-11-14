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
package okhttp3.sizelimiting

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.hamcrest.core.Is.`is`
import org.junit.Assert.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.Arrays

class ResponsePayloadSizeLimitInterceptorTest {

    @get:Rule
    val server = MockWebServer()

    private var httpClient: OkHttpClient? = null
    private var serviceUrl: HttpUrl? = null

    @Before
    fun setup() {
        val interceptor = ResponsePayloadSizeLimitInterceptor(TEST_SIZE_LIMIT.toLong())
        httpClient = OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build()

        serviceUrl = server.url("/")
    }

    @Test(expected = ContentTooLongException::class)
    @Throws(IOException::class)
    fun payloadExceedsLimit_contentLengthHeaderSet() {
        respondWithPayloadOfSize(TEST_SIZE_LIMIT + 1000, true)

        executeRequest()
    }

    @Test(expected = ContentTooLongException::class)
    @Throws(IOException::class)
    fun payloadExceedsLimit_contentLengthHeaderNotSet() {
        respondWithPayloadOfSize(TEST_SIZE_LIMIT + 1000, false)

        executeRequest()
    }

    @Test
    @Throws(IOException::class)
    fun payloadDoesNotExceedsLimit_contentLengthHeaderSet() {
        val responseValueSize = TEST_SIZE_LIMIT - 1
        respondWithPayloadOfSize(responseValueSize, true)
        val response = executeRequest()

        assertThat(response.body!!.bytes().size, `is`(responseValueSize))
    }

    @Test
    @Throws(IOException::class)
    fun payloadDoesNotExceedsLimit_contentLengthHeaderNotSet() {
        val responseValueSize = TEST_SIZE_LIMIT - 1
        respondWithPayloadOfSize(responseValueSize, false)
        val response = executeRequest()

        assertThat(response.body!!.bytes().size, `is`(responseValueSize))
    }

    @Test
    @Throws(IOException::class)
    fun willNotFailIfBodyIsEmpty() {
        respondWithEmptyBody()
        val response = executeRequest()

        assertThat(response.body!!.contentLength(), `is`(0L))
    }

    private fun respondWithPayloadOfSize(
      size: Int,
      includeContentLengthHeader: Boolean
    ) {

        val responseString = CharArray(size)
        Arrays.fill(responseString, 'A')
        val responseBody = String(responseString)

        var mockResponse = MockResponse().setBody(responseBody)
                .setHeader("Content-Type", PLAIN)

        if (includeContentLengthHeader) {
            mockResponse = mockResponse.setHeader("Content-Length", size.toString())
        }

        server.enqueue(mockResponse)
    }

    private fun respondWithEmptyBody() {
        server.enqueue(MockResponse())
    }

    @Throws(IOException::class)
    private fun executeRequest(): Response {
        val request = Request.Builder().url(serviceUrl!!)
        return httpClient!!.newCall(request.build()).execute()
    }

    companion object {
        private val PLAIN = "text/plain".toMediaType()
        private const val TEST_SIZE_LIMIT = 5000
    }
}
