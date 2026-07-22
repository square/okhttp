/*
 * Copyright (c) 2026 OkHttp Authors
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
@file:Suppress("ktlint:standard:filename")

package okhttp3.dnsoverhttps.internal

import java.io.IOException
import java.net.ProtocolException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps.Companion.DNS_MESSAGE
import okhttp3.dnsoverhttps.DnsOverHttps.Companion.MAX_RESPONSE_SIZE
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.dns.DnsCallStateMachine
import okhttp3.internal.dns.DnsMessage
import okhttp3.internal.dns.DnsMessageReader
import okhttp3.internal.dns.DnsMessageWriter
import okhttp3.internal.platform.Platform
import okio.Buffer
import okio.BufferedSink

// TODO: in-memory caching that uses timeToLive.
// TODO: honor Https.priority and Https.targetName. Create new calls!

/**
 * Implements [Dns.Call] by making multiple HTTPS calls.
 */
@OkHttpInternalApi
internal class DnsOverHttpsCall(
  override val request: Dns.Request,
  private val client: OkHttpClient,
  private val dnsUrl: HttpUrl,
  private val post: Boolean,
  includeIPv6: Boolean,
  includeServiceMetadata: Boolean,
  canceledException: IOException?,
) : Dns.Call,
  DnsCallStateMachine.Transport<Call>,
  Callback {
  private val stateMachine =
    DnsCallStateMachine(
      transport = this,
      call = this,
      canceledException = canceledException,
      includeIPv6 = includeIPv6,
      includeServiceMetadata = includeServiceMetadata,
    )

  override fun newQuery(dnsMessage: DnsMessage): Call {
    val queryParameter = dnsMessage.asQueryParameter()
    return client.newCall(
      request =
        Request
          .Builder()
          .header("Accept", DNS_MESSAGE.toString())
          .apply {
            if (post) {
              url(dnsUrl)
              cacheUrlOverride(
                dnsUrl
                  .newBuilder()
                  .addQueryParameter("query", queryParameter)
                  .build(),
              )
              post(QueryRequestBody(dnsMessage))
            } else {
              val requestUrl =
                dnsUrl
                  .newBuilder()
                  .addQueryParameter("dns", queryParameter)
                  .build()
              url(requestUrl)
            }
          }.build(),
    )
  }

  override fun enqueue(query: Call) {
    query.enqueue(this)
  }

  override fun cancel(query: Call) {
    query.cancel()
  }

  override fun onFailure(
    call: Call,
    e: IOException,
  ) {
    stateMachine.onQueryFailure(call, e)
  }

  override fun onResponse(
    call: Call,
    response: Response,
  ) {
    val dnsMessage =
      try {
        decodeResponse(response)
      } catch (e: IOException) {
        return stateMachine.onQueryFailure(call, e)
      }

    stateMachine.onQueryResponse(call, dnsMessage)
  }

  override fun enqueue(callback: Dns.Callback) {
    stateMachine.start(callback)
  }

  override fun cancel() {
    stateMachine.cancel()
  }

  override fun isCanceled() = stateMachine.canceled
}

internal fun DnsMessage.asQueryParameter(): String {
  val buffer = Buffer()
  DnsMessageWriter(buffer).write(this@asQueryParameter)
  return buffer.readByteString().base64Url(includePadding = false)
}

internal class QueryRequestBody(
  private val query: DnsMessage,
) : RequestBody() {
  override fun contentType() = DNS_MESSAGE

  override fun writeTo(sink: BufferedSink) {
    DnsMessageWriter(sink.buffer).write(query)
    sink.emitCompleteSegments()
  }
}

@Throws(IOException::class)
internal fun decodeResponse(response: Response): DnsMessage {
  if (
    response.cacheResponse == null &&
    response.protocol !== Protocol.HTTP_2 &&
    response.protocol !== Protocol.QUIC
  ) {
    Platform.get().log("Unexpected protocol: ${response.protocol}", Platform.WARN)
  }

  response.use {
    if (!response.isSuccessful) {
      throw IOException("response: ${response.code} ${response.message}")
    }

    val body = response.body
    if (body.contentLength() > MAX_RESPONSE_SIZE) {
      throw ProtocolException(
        "response size exceeds limit ($MAX_RESPONSE_SIZE bytes): ${body.contentLength()} bytes",
      )
    }

    return DnsMessageReader(body.source()).read()
  }
}
