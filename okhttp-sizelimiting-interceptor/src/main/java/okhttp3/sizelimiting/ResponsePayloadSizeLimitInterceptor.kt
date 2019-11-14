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

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.OkHttpClient
import okio.BufferedSource
import okio.buffer
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

/**
 * An OkHTTP Interceptor which limits the HTTP response size.
 *
 *
 * It is intended to protect caller from unwanted large payloads.
 * Can be applied as [application interceptor][OkHttpClient.interceptors]
 *
 * @param maxContentLength maximum payload size in bytes
 */
class ResponsePayloadSizeLimitInterceptor(private val maxContentLength: Long) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {

        val originalResponse = chain.proceed(chain.request())
        try {
            originalResponse.body ?: return originalResponse

            checkContentLengthHeader(originalResponse)

            checkContentLength(originalResponse)

            return originalResponse.newBuilder().body(
                    wrapResponseBodyInLengthLimiter(originalResponse.body, originalResponse)
            ).build()
        } catch (ex: Exception) {
            // In case of any error we must close the response to avoid connection leaking
            originalResponse.close()
            throw ex
        }
    }

    @Throws(ContentTooLongException::class)
    private fun checkContentLengthHeader(originalResponse: Response) {
        val contentLengthHeader = originalResponse.header("Content-Length") ?: return

        try {
            val contentLengthValue = java.lang.Long.parseLong(contentLengthHeader)
            if (contentLengthValue > maxContentLength) {
                closeResponseAndThrowContentLengthExceededError(contentLengthValue)
            }
        } catch (ex: NumberFormatException) {
            LOGGER.log(Level.WARNING, "Received malformed Content-Length header: " +
                    contentLengthHeader)
        }
    }

    @Throws(ContentTooLongException::class)
    private fun closeResponseAndThrowContentLengthExceededError(contentLengthValue: Long) {
        throw ContentTooLongException("Response exceeds allowed size. Allowed: $maxContentLength " +
                "Response size: $contentLengthValue")
    }

    @Throws(ContentTooLongException::class)
    private fun checkContentLength(originalResponse: Response) {
        val responseSize = originalResponse.body!!.contentLength()
        if (responseSize > maxContentLength) {
            closeResponseAndThrowContentLengthExceededError(responseSize)
        }
    }

    private fun wrapResponseBodyInLengthLimiter(
      originalResponseBody: ResponseBody?,
      originalResponse: Response
    ): ResponseBody {
        return object : ResponseBody() {
            override fun contentType(): MediaType? {
                return originalResponseBody!!.contentType()
            }

            override fun contentLength(): Long {
                return originalResponseBody!!.contentLength()
            }

            override fun source(): BufferedSource {
                val cleanupResources = {
                    originalResponse.use {
                        originalResponseBody?.close()
                    }
                }
                return LengthLimitingSource(originalResponseBody!!.source(),
                        maxContentLength, cleanupResources).buffer()
            }
        }
    }

    companion object {
        private val LOGGER = Logger.getLogger(ResponsePayloadSizeLimitInterceptor::class.java.name)
    }
}
