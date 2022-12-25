/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Suppress("HttpUrlsUsage") // Don't warn if we should be using https://.
open class HttpUrlCommonTest {
  protected open fun parse(url: String): HttpUrl {
    return url.toHttpUrl()
  }

  protected open fun assertInvalid(string: String, exceptionMessage: String?) {
    assertThat(string.toHttpUrlOrNull()).isNull()
  }

  @Test
  fun scheme() {
    assertThat(parse("http://host/")).isEqualTo(parse("http://host/"))
    assertThat(parse("Http://host/")).isEqualTo(parse("http://host/"))
    assertThat(parse("http://host/")).isEqualTo(parse("http://host/"))
    assertThat(parse("HTTP://host/")).isEqualTo(parse("http://host/"))
    assertThat(parse("https://host/")).isEqualTo(parse("https://host/"))
    assertThat(parse("HTTPS://host/")).isEqualTo(parse("https://host/"))
    assertInvalid(
      "image640://480.png",
      "Expected URL scheme 'http' or 'https' but was 'image640'"
    )
    assertInvalid("httpp://host/", "Expected URL scheme 'http' or 'https' but was 'httpp'")
    assertInvalid(
      "0ttp://host/",
      "Expected URL scheme 'http' or 'https' but no scheme was found for 0ttp:/..."
    )
    assertInvalid("ht+tp://host/", "Expected URL scheme 'http' or 'https' but was 'ht+tp'")
    assertInvalid("ht.tp://host/", "Expected URL scheme 'http' or 'https' but was 'ht.tp'")
    assertInvalid("ht-tp://host/", "Expected URL scheme 'http' or 'https' but was 'ht-tp'")
    assertInvalid("ht1tp://host/", "Expected URL scheme 'http' or 'https' but was 'ht1tp'")
    assertInvalid("httpss://host/", "Expected URL scheme 'http' or 'https' but was 'httpss'")
  }

  @Test
  fun parseNoScheme() {
    assertInvalid(
      "//host",
      "Expected URL scheme 'http' or 'https' but no scheme was found for //host"
    )
    assertInvalid(
      "://host",
      "Expected URL scheme 'http' or 'https' but no scheme was found for ://hos..."
    )
    assertInvalid(
      "/path",
      "Expected URL scheme 'http' or 'https' but no scheme was found for /path"
    )
    assertInvalid(
      "path",
      "Expected URL scheme 'http' or 'https' but no scheme was found for path"
    )
    assertInvalid(
      "?query",
      "Expected URL scheme 'http' or 'https' but no scheme was found for ?query"
    )
    assertInvalid(
      "#fragment",
      "Expected URL scheme 'http' or 'https' but no scheme was found for #fragm..."
    )
  }

  @Test
  fun hostIpv4CanonicalForm() {
    assertThat(parse("http://255.255.255.255/").host).isEqualTo("255.255.255.255")
    assertThat(parse("http://1.2.3.4/").host).isEqualTo("1.2.3.4")
    assertThat(parse("http://0.0.0.0/").host).isEqualTo("0.0.0.0")
  }

  @Test
  fun hostWithTrailingDot() {
    assertThat(parse("http://host./").host).isEqualTo("host.")
  }

  @Test
  fun port() {
    assertThat(parse("http://host:80/")).isEqualTo(parse("http://host/"))
    assertThat(parse("http://host:99/")).isEqualTo(parse("http://host:99/"))
    assertThat(parse("http://host:/")).isEqualTo(parse("http://host/"))
    assertThat(parse("http://host:65535/").port).isEqualTo(65535)
    assertInvalid("http://host:0/", "Invalid URL port: \"0\"")
    assertInvalid("http://host:65536/", "Invalid URL port: \"65536\"")
    assertInvalid("http://host:-1/", "Invalid URL port: \"-1\"")
    assertInvalid("http://host:a/", "Invalid URL port: \"a\"")
    assertInvalid("http://host:%39%39/", "Invalid URL port: \"%39%39\"")
  }

  @Test
  fun minimalUrlComposition() {
    val url = HttpUrl.Builder().scheme("http").host("host").build()
    assertThat(url.toString()).isEqualTo("http://host/")
    assertThat(url.scheme).isEqualTo("http")
    assertThat(url.username).isEqualTo("")
    assertThat(url.password).isEqualTo("")
    assertThat(url.host).isEqualTo("host")
    assertThat(url.port).isEqualTo(80)
    assertThat(url.encodedPath).isEqualTo("/")
    assertThat(url.query).isNull()
    assertThat(url.fragment).isNull()
  }

  @Test
  fun fullUrlComposition() {
    val url = HttpUrl.Builder()
      .scheme("http")
      .username("username")
      .password("password")
      .host("host")
      .port(8080)
      .addPathSegment("path")
      .query("query")
      .fragment("fragment")
      .build()
    assertThat(url.toString())
      .isEqualTo("http://username:password@host:8080/path?query#fragment")
    assertThat(url.scheme).isEqualTo("http")
    assertThat(url.username).isEqualTo("username")
    assertThat(url.password).isEqualTo("password")
    assertThat(url.host).isEqualTo("host")
    assertThat(url.port).isEqualTo(8080)
    assertThat(url.encodedPath).isEqualTo("/path")
    assertThat(url.query).isEqualTo("query")
    assertThat(url.fragment).isEqualTo("fragment")
  }
}
