/*
 * Copyright 2022 Google LLC
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
package okhttp3.android.httpengine

/** Defines a redirect strategy for the Cronet OkHttp transport layer.  */
abstract class RedirectStrategy private constructor() {
  /**
   * Returns whether redirects should be followed at all. If set to false, the redirect response
   * will be returned.
   */
  abstract fun followRedirects(): Boolean

  /**
   * Returns the maximum number of redirects to follow. If more redirects are attempted an exception
   * should be thrown by the component handling the request. Shouldn't be called at all if [ ][.followRedirects] return false.
   */
  abstract fun numberOfRedirectsToFollow(): Int

  internal object WithoutRedirectsHolder : RedirectStrategy() {
    override fun followRedirects(): Boolean = false

    override fun numberOfRedirectsToFollow(): Int = throw UnsupportedOperationException()
  }

  internal object DefaultRedirectsHolder : RedirectStrategy() {
    override fun followRedirects(): Boolean = true

    override fun numberOfRedirectsToFollow(): Int = DEFAULT_REDIRECTS
  }

  companion object {
    /** The default number of redirects to follow. Should be less than the Chromium wide safeguard.  */
    private const val DEFAULT_REDIRECTS = 16

    /**
     * Returns a strategy which will not follow redirects.
     *
     *
     * Note that because of Cronet's limitations
     * (https://developer.android.com/guide/topics/connectivity/cronet/lifecycle#overview) it is
     * impossible to retrieve the body of a redirect response. As a result, a dummy empty body will
     * always be provided.
     */
    fun withoutRedirects(): RedirectStrategy = WithoutRedirectsHolder

    /**
     * Returns a strategy which will follow redirects up to [.DEFAULT_REDIRECTS] times. If more
     * redirects are attempted an exception is thrown.
     */
    fun defaultStrategy(): RedirectStrategy = DefaultRedirectsHolder
  }
}
