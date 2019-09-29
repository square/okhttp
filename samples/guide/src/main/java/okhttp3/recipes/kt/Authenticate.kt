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

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

class Authenticate {
  private val client = OkHttpClient.Builder()
      .authenticator(object : Authenticator {
        @Throws(IOException::class)
        override fun authenticate(route: Route?, response: Response): Request? {
          if (response.request.header("Authorization") != null) {
            return null // Give up, we've already attempted to authenticate.
          }

          println("Authenticating for response: $response")
          println("Challenges: ${response.challenges()}")
          val credential = Credentials.basic("jesse", "password1")
          return response.request.newBuilder()
              .header("Authorization", credential)
              .build()
        }
      })
      .build()

  fun run() {
    val request = Request.Builder()
        .url("http://publicobject.com/secrets/hellosecret.txt")
        .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Unexpected code $response")

      println(response.body!!.string())
    }
  }
}

fun main() {
  Authenticate().run()
}
