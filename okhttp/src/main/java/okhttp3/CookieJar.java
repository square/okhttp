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
package okhttp3;

import java.net.HttpCookie;

/**
 * Provides <strong>policy</strong> and <strong>persistence</strong> for HTTP cookies.
 *
 * <p>As policy, implementations of this interface are responsible for selecting which cookies
 * to accept and which to reject. A reasonable policy is to reject all cookies, though that may be
 * interfere with session-based authentication schemes that require cookies.
 *
 * <p>As persistence, implementations of this interface must also provide storage of cookies. Simple
 * implementations may store cookies in memory; sophisticated ones may use the file system or
 * database to hold accepted cookies. The implementation should delete cookies that have expired
 * and limit the resources required for cookie storage.
 */
public interface CookieJar {
  /** A cookie jar that never accepts any cookies. */
  CookieJar NO_COOKIES = new CookieJar() {
    @Override public void saveFromResponse(HttpUrl url, Headers headers) {
    }
    @Override public Headers loadForRequest(HttpUrl url, Headers headers) {
      return headers;
    }
  };

  /**
   * Saves cookies from HTTP response headers to this store according to this jar's policy.
   *
   * <p>Most implementations should use {@link java.net.HttpCookie#parse HttpCookie.parse()} to
   * convert raw HTTP header strings into a cookie model.
   *
   * <p>Note that this method may be called a second time for a single HTTP response if the response
   * includes a trailer. For this obscure HTTP feature, {@code headers} contains only the trailer
   * fields.
   *
   * <p><strong>Warning:</strong> it is the implementor's responsibility to reject cookies that
   * don't {@linkplain HttpCookie#domainMatches match} {@code url}. Otherwise an attacker on {@code
   * https://attacker.com/} may set cookies for {@code https://victim.com/}, resulting in session
   * fixation.
   */
  void saveFromResponse(HttpUrl url, Headers headers);

  /**
   * Load cookies from the jar for an HTTP request to {@code url}. This method returns the full set
   * of headers for the network request; this is typically a superset of {@code headers}.
   *
   * <p>Simple implementations will return the accepted cookies that have not yet expired and that
   * {@linkplain HttpCookie#domainMatches match} {@code url}.
   */
  Headers loadForRequest(HttpUrl url, Headers headers);
}
