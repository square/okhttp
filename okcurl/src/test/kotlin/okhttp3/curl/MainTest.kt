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
package okhttp3.curl

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.startsWith
import java.io.IOException
import kotlin.test.Test
import okhttp3.RequestBody
import okio.Buffer

class MainTest {
  @Test
  fun simple() {
    val request = fromArgs("http://example.com").createRequest()
    assertThat(request.method).isEqualTo("GET")
    assertThat(request.url.toString()).isEqualTo("http://example.com/")
    assertThat(request.body).isNull()
  }

  @Test
  @Throws(IOException::class)
  fun put() {
    val request = fromArgs("-X", "PUT", "-d", "foo", "http://example.com").createRequest()
    assertThat(request.method).isEqualTo("PUT")
    assertThat(request.url.toString()).isEqualTo("http://example.com/")
    assertThat(request.body!!.contentLength()).isEqualTo(3)
  }

  @Test
  fun dataPost() {
    val request = fromArgs("-d", "foo", "http://example.com").createRequest()
    val body = request.body
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.url.toString()).isEqualTo("http://example.com/")
    assertThat(body!!.contentType().toString()).isEqualTo(
      "application/x-www-form-urlencoded; charset=utf-8",
    )
    assertThat(bodyAsString(body)).isEqualTo("foo")
  }

  @Test
  fun dataPut() {
    val request = fromArgs("-d", "foo", "-X", "PUT", "http://example.com").createRequest()
    val body = request.body
    assertThat(request.method).isEqualTo("PUT")
    assertThat(request.url.toString()).isEqualTo("http://example.com/")
    assertThat(body!!.contentType().toString()).isEqualTo(
      "application/x-www-form-urlencoded; charset=utf-8",
    )
    assertThat(bodyAsString(body)).isEqualTo("foo")
  }

  @Test
  fun contentTypeHeader() {
    val request =
      fromArgs(
        "-d",
        "foo",
        "-H",
        "Content-Type: application/json",
        "http://example.com",
      ).createRequest()
    val body = request.body
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.url.toString()).isEqualTo("http://example.com/")
    assertThat(body!!.contentType().toString())
      .isEqualTo("application/json; charset=utf-8")
    assertThat(bodyAsString(body)).isEqualTo("foo")
  }

  @Test
  fun referer() {
    val request = fromArgs("-e", "foo", "http://example.com").createRequest()
    assertThat(request.method).isEqualTo("GET")
    assertThat(request.url.toString()).isEqualTo("http://example.com/")
    assertThat(request.header("Referer")).isEqualTo("foo")
    assertThat(request.body).isNull()
  }

  @Test
  fun userAgent() {
    val request = fromArgs("-A", "foo", "http://example.com").createRequest()
    assertThat(request.method).isEqualTo("GET")
    assertThat(request.url.toString()).isEqualTo("http://example.com/")
    assertThat(request.header("User-Agent")).isEqualTo("foo")
    assertThat(request.body).isNull()
  }

  @Test
  fun defaultUserAgent() {
    val request = fromArgs("http://example.com").createRequest()
    assertThat(request.header("User-Agent")!!).startsWith("okcurl/")
  }

  @Test
  fun headerSplitWithDate() {
    val request =
      fromArgs(
        "-H",
        "If-Modified-Since: Mon, 18 Aug 2014 15:16:06 GMT",
        "http://example.com",
      ).createRequest()
    assertThat(request.header("If-Modified-Since")).isEqualTo(
      "Mon, 18 Aug 2014 15:16:06 GMT",
    )
  }

  companion object {
    fun fromArgs(vararg args: String): Main {
      return Main().apply {
        parse(args.toList())
      }
    }

    private fun bodyAsString(body: RequestBody?): String {
      return try {
        val buffer = Buffer()
        body!!.writeTo(buffer)
        buffer.readString(body.contentType()!!.charset()!!)
      } catch (e: IOException) {
        throw RuntimeException(e)
      }
    }
  }
}
