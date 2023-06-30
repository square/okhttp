/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package okhttp3

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.internal.MockWebServerExtension
import okhttp3.tls.internal.TlsUtil
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.Executors

@ExtendWith(MockWebServerExtension::class)
class InterceptorTest {
    @RegisterExtension
    val clientTestRule = OkHttpClientTestRule()

    private lateinit var server: MockWebServer

    private val clientBuilder: OkHttpClient.Builder = clientTestRule.newClientBuilder()

    val request by lazy { Request(server.url("/")) }

    // TODO parameterize
    val tls: Boolean = true

    @BeforeEach
    fun setup(server: MockWebServer) {
        this.server = server

        clientBuilder.addInterceptor { chain ->
            chain.proceed(chain.request()).also {
                if (tls) {
                    check(it.protocol == Protocol.HTTP_2)
                    check(it.handshake != null)
                } else {
                    check(it.protocol == Protocol.HTTP_1_1)
                    check(it.handshake == null)
                }
            }
        }

        if (tls) {
            enableTls()
        }

        server.enqueue(MockResponse(body = "failed", code = 401))
        server.enqueue(MockResponse(body = "token"))
        server.enqueue(MockResponse(body = "abc"))
    }

    @Test
    fun asyncCallTest() {
        runTest {
            val interceptor = Interceptor {
                val response = it.proceed(it.request())

                if (response.code == 401 && it.request().url.encodedPath != "/token") {
                    check(response.body.string() == "failed")
                    response.close()

                    val tokenRequest = Request(server.url("/token"))
                    val call = it.callFactory.newCall(tokenRequest)
                    val token = runBlocking {
                        val tokenResponse = call.executeAsync()
                        withContext(Dispatchers.IO) {
                            tokenResponse.body.string()
                        }
                    }

                    check(token == "token")

                    val secondResponse = it.proceed(
                        it.request().newBuilder()
                            .header("Authorization", token)
                            .build()
                    )

                    secondResponse
                } else {
                    response
                }
            }


            val client = clientBuilder
                .dispatcher(Dispatcher(Executors.newSingleThreadExecutor()))
                .addInterceptor(interceptor)
                .build()

            val call = client.newCall(request)

            val tokenResponse = call.executeAsync()
            val body = withContext(Dispatchers.IO) {
                tokenResponse.body.string()
            }

            assertThat(body).isEqualTo("abc")
        }
    }

    @Test
    fun syncCallTest() {
        val interceptor = Interceptor {
            val response = it.proceed(it.request())

            if (response.code == 401 && it.request().url.encodedPath != "/token") {
                check(response.body.string() == "failed")
                response.close()

                val tokenRequest = Request(server.url("/token"))
                val call = it.callFactory.newCall(tokenRequest)
                val token = if (false)
                    runBlocking {
                        val tokenResponse = call.executeAsync()
                        withContext(Dispatchers.IO) {
                            tokenResponse.body.string()
                        }
                    }
                else
                    call.execute().body.string()

                check(token == "token")

                val secondResponse = it.proceed(
                    it.request().newBuilder()
                        .header("Authorization", token)
                        .build()
                )

                secondResponse
            } else {
                response
            }
        }

        val client = clientBuilder
            .dispatcher(Dispatcher(Executors.newSingleThreadExecutor()))
            .addInterceptor(interceptor)
            .build()

        val call = client.newCall(request)

        val body = call.execute().body.string()

        assertThat(body).isEqualTo("abc")
    }

    @Test
    fun asyncInterceptorCallTest() {
        runTest {
            val interceptor = SuspendingInterceptor {
                val response = it.proceed(it.request())

                if (response.code == 401 && it.request().url.encodedPath != "/token") {
                    check(response.body.string() == "failed")
                    response.close()

                    val tokenRequest = Request(server.url("/token"))
                    val call = it.callFactory.newCall(tokenRequest)

                    val tokenResponse = call.executeAsync()
                    val token = withContext(Dispatchers.IO) {
                        tokenResponse.body.string()
                    }

                    check(token == "token")

                    val secondResponse = it.proceed(
                        it.request().newBuilder()
                            .header("Authorization", token)
                            .build()
                    )

                    secondResponse
                } else {
                    response
                }
            }


            val client = clientBuilder
                .dispatcher(Dispatcher(Executors.newSingleThreadExecutor()))
                .addInterceptor(interceptor)
                .build()

            val call = client.newCall(request)

            val tokenResponse = call.executeAsync()
            val body = withContext(Dispatchers.IO) {
                tokenResponse.body.string()
            }

            assertThat(body).isEqualTo("abc")
        }
    }

    @Test
    fun asyncInterceptorCallByThreadTest() {
        lateinit var client: OkHttpClient

        runTest {
            val interceptor = SuspendingInterceptor {
                val response = it.proceed(it.request())

                if (response.code == 401 && it.request().url.encodedPath != "/token") {
                    check(response.body.string() == "failed")
                    response.close()

                    val tokenRequest = Request(server.url("/token"))
                    val call = client.newCall(tokenRequest)

                    val tokenResponse = call.executeAsync()
                    val token = withContext(Dispatchers.IO) {
                        tokenResponse.body.string()
                    }

                    check(token == "token")

                    val secondResponse = it.proceed(
                        it.request().newBuilder()
                            .header("Authorization", token)
                            .build()
                    )

                    secondResponse
                } else {
                    response
                }
            }

            client = clientBuilder
                .dispatcher(Dispatcher(Executors.newSingleThreadExecutor()))
                .addInterceptor(interceptor)
                .build()

            val call = client.newCall(request)

            val tokenResponse = call.executeAsync()
            val body = withContext(Dispatchers.IO) {
                tokenResponse.body.string()
            }

            assertThat(body).isEqualTo("abc")
        }
    }

    private fun enableTls() {
        val certs = TlsUtil.localhost()

        clientBuilder
            .sslSocketFactory(
                certs.sslSocketFactory(), certs.trustManager
            )

        server.useHttps(certs.sslSocketFactory())
    }
}
