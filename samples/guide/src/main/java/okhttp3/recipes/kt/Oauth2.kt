/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.recipes.kt

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.io.IOException
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class Oauth2(val port: Int) : HttpHandler {
  val server: HttpServer = HttpServer.create(InetSocketAddress("localhost", port), 1)

  var code: String? = null
  var accessToken: String? = null
  var refreshToken: String? = null

  val latch = CountDownLatch(1)

  val redirect = "http://localhost:3000/Callback"
  val clientId = "xxx"

  val client = OkHttpClient.Builder()
    .addInterceptor { chain ->
      val url = chain.request().url
      if (accessToken != null && url.host == "api.twitter.com" && !url.encodedPath.contains("oauth")) {
        val authedRequest = chain.request().newBuilder()
          .header("Authorization", "Bearer $accessToken")
          .build()

        var response = chain.proceed(authedRequest)

        if (response.code == 401) {
          refreshAuth()

          val reauthedRequest = chain.request().newBuilder()
            .header("Authorization", "Bearer $accessToken")
            .build()

          response = chain.proceed(
            reauthedRequest
          )
        }


        response
      } else {
        chain.proceed(chain.request())
      }
    }
    .build()

  init {
    server.createContext("/", this)
    server.start()

    println("Started on $port")
  }

  override fun handle(exchange: HttpExchange) {
    exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    exchange.sendResponseHeaders(200, 0)

    PrintWriter(exchange.responseBody).use { out ->
      out.println("Success!")

      // TODO check state
      try {
        code = "http://localhost${exchange.requestURI}".toHttpUrl().queryParameter("code")
      } catch (e: Exception) {
        e.printStackTrace()
      }

      latch.countDown()
    }

    exchange.close()
  }

  fun run() {
    // https://developer.twitter.com/en/docs/authentication/oauth-2-0/user-access-token

    val authPage =
      "https://twitter.com/i/oauth2/authorize?response_type=code&client_id=$clientId&redirect_uri=$redirect&scope=tweet.read%20users.read%20offline.access&state=state&code_challenge=challenge&code_challenge_method=plain"
    Desktop.getDesktop().browse(URI.create(authPage))

    latch.await()

    exchangeToken()

    meRequest()
  }

  private fun meRequest() {
    val meRequest = Request.Builder()
      .url("https://api.twitter.com/2/users/me")
      .build()

    client.newCall(meRequest).execute().use { response ->
      println(response.body?.string())
    }
  }

  private fun refreshAuth() {
    val request = Request.Builder()
      .url("https://api.twitter.com/2/oauth2/token")
      .post(
        FormBody.Builder()
          .add("grant_type", "refresh_token")
          .add("client_id", clientId)
          .add("refresh_token", refreshToken!!)
          .build()
      )
      .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Unexpected code $response")

      val body = response.body!!.string()
      accessToken = "\"access_token\":\"([^\"]+)\"".toRegex().find(body)?.groupValues?.get(1)
      refreshToken = "\"refresh_token\":\"([^\"]+)\"".toRegex().find(body)?.groupValues?.get(1)
    }
  }

  private fun exchangeToken() {
    val request = Request.Builder()
      .url("https://api.twitter.com/2/oauth2/token")
      .post(
        FormBody.Builder()
          .add("grant_type", "authorization_code")
          .add("client_id", clientId)
          .add("code", code!!)
          .add("redirect_uri", redirect)
          .add("code_verifier", "challenge")
          .build()
      )
      .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Unexpected code $response")

      val body = response.body!!.string()
      accessToken = "\"access_token\":\"([^\"]+)\"".toRegex().find(body)?.groupValues?.get(1)
      refreshToken = "\"refresh_token\":\"([^\"]+)\"".toRegex().find(body)?.groupValues?.get(1)
    }
  }
}

fun main() {
  Oauth2(3000).run()
}
