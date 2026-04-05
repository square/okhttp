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

import java.io.File
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

class UploadProgress {
  companion object {
    private const val IMGUR_CLIENT_ID = "9199fdef135c122"
    private val MEDIA_TYPE_PNG = "image/png".toMediaType()
  }

  private val client = OkHttpClient()

  @Throws(Exception::class)
  fun run() {
    val progressListener =
      object : ProgressListener {
        private var firstUpdate = true

        override fun update(
          bytesWritten: Long,
          contentLength: Long,
          done: Boolean,
        ) {
          if (done) {
            println("completed")
          } else {
            if (firstUpdate) {
              firstUpdate = false
              if (contentLength == -1L) {
                println("content-length: unknown")
              } else {
                println("content-length: $contentLength")
              }
            }
            println(bytesWritten)
            if (contentLength != -1L) {
              println("${100 * bytesWritten / contentLength}% done")
            }
          }
        }
      }

    val file = File("docs/images/logo-square.png")
    val requestBody: RequestBody = file.asRequestBody(MEDIA_TYPE_PNG)

    val request =
      Request
        .Builder()
        .header("Authorization", "Client-ID $IMGUR_CLIENT_ID")
        .url("https://api.imgur.com/3/image")
        .post(ProgressRequestBody(requestBody, progressListener))
        .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Unexpected code $response")
      println(response.body.string())
    }
  }

  private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val progressListener: ProgressListener,
  ) : RequestBody() {
    override fun contentType() = delegate.contentType()

    @Throws(IOException::class)
    override fun contentLength(): Long = delegate.contentLength()

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
      val forwardingSink =
        object : ForwardingSink(sink) {
          private var totalBytesWritten: Long = 0
          private var completed = false

          override fun write(
            source: Buffer,
            byteCount: Long,
          ) {
            super.write(source, byteCount)
            totalBytesWritten += byteCount
            progressListener.update(totalBytesWritten, contentLength(), completed)
          }

          override fun close() {
            super.close()
            if (!completed) {
              completed = true
              progressListener.update(totalBytesWritten, contentLength(), completed)
            }
          }
        }

      val bufferedSink = forwardingSink.buffer()
      delegate.writeTo(bufferedSink)
      bufferedSink.flush()
    }
  }

  fun interface ProgressListener {
    fun update(
      bytesWritten: Long,
      contentLength: Long,
      done: Boolean,
    )
  }
}

fun main() {
  UploadProgress().run()
}
