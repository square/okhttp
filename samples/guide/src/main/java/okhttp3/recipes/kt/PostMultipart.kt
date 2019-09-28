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

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

class PostMultipart {
  private val client = OkHttpClient()

  fun run() {
    // Use the imgur image upload API as documented at https://api.imgur.com/endpoints/image
    val requestBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("title", "Square Logo")
        .addFormDataPart("image", "logo-square.png",
            File("docs/images/logo-square.png").asRequestBody(MEDIA_TYPE_PNG))
        .build()

    val request = Request.Builder()
        .header("Authorization", "Client-ID $IMGUR_CLIENT_ID")
        .url("https://api.imgur.com/3/image")
        .post(requestBody)
        .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Unexpected code $response")

      println(response.body!!.string())
    }
  }

  companion object {
    /**
     * The imgur client ID for OkHttp recipes. If you're using imgur for anything other than running
     * these examples, please request your own client ID! https://api.imgur.com/oauth2
     */
    private val IMGUR_CLIENT_ID = "9199fdef135c122"
    private val MEDIA_TYPE_PNG = "image/png".toMediaType()
  }
}

fun main() {
  PostMultipart().run()
}
