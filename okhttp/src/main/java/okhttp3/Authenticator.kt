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

import java.io.IOException

/**
 * Performs either **preemptive** authentication before connecting to a proxy server, or
 * **reactive** authentication after receiving a challenge from either an origin web server or proxy
 * server.
 *
 * ## Preemptive Authentication
 *
 * To make HTTPS calls using an HTTP proxy server OkHttp must first negotiate a connection with
 * the proxy. This proxy connection is called a "TLS Tunnel" and is specified by
 * [RFC 2817][1]. The HTTP CONNECT request that creates this tunnel connection is special: it
 * does not participate in any [interceptors][Interceptor] or [event listeners][EventListener]. It
 * doesn't include the motivating request's HTTP headers or even its full URL; only the target
 * server's hostname is sent to the proxy.
 *
 * Prior to sending any CONNECT request OkHttp always calls the proxy authenticator so that it may
 * prepare preemptive authentication. OkHttp will call [authenticate] with a fake `HTTP/1.1 407
 * Proxy Authentication Required` response that has a `Proxy-Authenticate: OkHttp-Preemptive`
 * challenge. The proxy authenticator may return either either an authenticated request, or null to
 * connect without authentication.
 *
 * ```
 * for (Challenge challenge : response.challenges()) {
 *   // If this is preemptive auth, use a preemptive credential.
 *   if (challenge.scheme().equalsIgnoreCase("OkHttp-Preemptive")) {
 *     return response.request().newBuilder()
 *         .header("Proxy-Authorization", "secret")
 *         .build();
 *   }
 * }
 * return null; // Didn't find a preemptive auth scheme.
 * ```
 *
 * ## Reactive Authentication
 *
 * Implementations authenticate by returning a follow-up request that includes an authorization
 * header, or they may decline the challenge by returning null. In this case the unauthenticated
 * response will be returned to the caller that triggered it.
 *
 * Implementations should check if the initial request already included an attempt to
 * authenticate. If so it is likely that further attempts will not be useful and the authenticator
 * should give up.
 *
 * When reactive authentication is requested by an origin web server, the response code is 401
 * and the implementation should respond with a new request that sets the "Authorization" header.
 *
 * ```
 * if (response.request().header("Authorization") != null) {
 *   return null; // Give up, we've already failed to authenticate.
 * }
 *
 * String credential = Credentials.basic(...)
 * return response.request().newBuilder()
 *     .header("Authorization", credential)
 *     .build();
 * ```
 *
 * When reactive authentication is requested by a proxy server, the response code is 407 and the
 * implementation should respond with a new request that sets the "Proxy-Authorization" header.
 *
 * ```
 * if (response.request().header("Proxy-Authorization") != null) {
 *   return null; // Give up, we've already failed to authenticate.
 * }
 *
 * String credential = Credentials.basic(...)
 * return response.request().newBuilder()
 *     .header("Proxy-Authorization", credential)
 *     .build();
 * ```
 *
 * The proxy authenticator may implement preemptive authentication, reactive authentication, or
 * both.
 *
 * Applications may configure OkHttp with an authenticator for origin servers, or proxy servers,
 * or both.
 *
 * [1]: https://tools.ietf.org/html/rfc2817
 */
interface Authenticator {
  /**
   * Returns a request that includes a credential to satisfy an authentication challenge in
   * [response]. Returns null if the challenge cannot be satisfied.
   *
   * The route is best effort, it currently may not always be provided even when logically
   * available. It may also not be provided when an authenticator is re-used manually in an
   * application interceptor, such as when implementing client-specific retries.
   */
  @Throws(IOException::class)
  fun authenticate(route: Route?, response: Response): Request?

  companion object {
    /** An authenticator that knows no credentials and makes no attempt to authenticate. */
    @JvmField
    val NONE = object : Authenticator {
      override fun authenticate(route: Route?, response: Response): Request? = null
    }
  }
}
