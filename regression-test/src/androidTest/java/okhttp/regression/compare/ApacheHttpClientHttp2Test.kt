/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package okhttp.regression.compare

import org.apache.hc.client5.http.async.methods.SimpleHttpRequests
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer
import org.apache.hc.client5.http.async.methods.SimpleResponseConsumer
import org.apache.hc.client5.http.impl.async.HttpAsyncClients
import org.apache.hc.client5.http.protocol.HttpClientContext
import org.apache.hc.core5.concurrent.FutureCallback
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.http.ProtocolVersion
import org.apache.hc.core5.io.CloseMode
import org.junit.Assert
import org.junit.Test

/**
 * https://hc.apache.org/httpcomponents-client-5.0.x/httpclient5/examples/AsyncClientTlsAlpn.java
 */
class ApacheHttpClientHttp2Test {
  @Test
  fun testHttp2() {
    val client = HttpAsyncClients.createHttp2Default()

    client.use { client ->
        client.start()
        val target = HttpHost("https", "google.com", 443)
        val requestUri = "/robots.txt"
        val clientContext = HttpClientContext.create()
        val request = SimpleHttpRequests.get(target, requestUri)
        val future = client.execute(
          SimpleRequestProducer.create(request),
          SimpleResponseConsumer.create(),
          clientContext,
          object : FutureCallback<SimpleHttpResponse> {
            override fun completed(response: SimpleHttpResponse) {
              println("Protocol " + response.version)
              println(requestUri + "->" + response.code)
              println(response.body)
              val sslSession = clientContext.sslSession
              if (sslSession != null) {
                println("SSL protocol " + sslSession.protocol)
                println("SSL cipher suite " + sslSession.cipherSuite)
              }
              Assert.assertEquals(ProtocolVersion("HTTP", 2, 0), response.version)
            }

            override fun failed(ex: Exception) {
              println("$requestUri->$ex")
            }

            override fun cancelled() {
              println("$requestUri cancelled")
            }
          })
        future.get()
        println("Shutting down")
        client.close(CloseMode.GRACEFUL)
      }
  }
}