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

/**
 * Provides **policy** and **persistence** for HTTP cookies.
 *
 * As policy, implementations of this interface are responsible for selecting which cookies to
 * accept and which to reject. A reasonable policy is to reject all cookies, though that may
 * interfere with session-based authentication schemes that require cookies.
 *
 * As persistence, implementations of this interface must also provide storage of cookies. Simple
 * implementations may store cookies in memory; sophisticated ones may use the file system or
 * database to hold accepted cookies. The [cookie storage model][rfc_6265_53] specifies policies for
 * updating and expiring cookies.
 *
 * [rfc_6265_53]: https://tools.ietf.org/html/rfc6265#section-5.3
 */
interface CookieJar {
  /**
   * Saves [cookies] from an HTTP response to this store according to this jar's policy.
   *
   * Note that this method may be called a second time for a single HTTP response if the response
   * includes a trailer. For this obscure HTTP feature, [cookies] contains only the trailer's
   * cookies.
   */
  fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>)

  /**
   * Load cookies from the jar for an HTTP request to [url]. This method returns a possibly
   * empty list of cookies for the network request.
   *
   * Simple implementations will return the accepted cookies that have not yet expired and that
   * [match][Cookie.matches] [url].
   */
  fun loadForRequest(url: HttpUrl): List<Cookie>

  companion object {
    /** A cookie jar that never accepts any cookies. */
    @JvmField
    val NO_COOKIES: CookieJar = object : CookieJar {
      override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
      }

      override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return emptyList()
      }
    }
  }
}
