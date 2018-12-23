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
package okhttp3;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import javax.annotation.Nullable;

import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableMap;
import static java.util.Locale.US;
import static okhttp3.internal.Util.ISO_8859_1;

/** An RFC 7235 challenge. */
public final class Challenge {
  private final String scheme;
  private final Map<String, String> authParams;

  public Challenge(String scheme, Map<String, String> authParams) {
    if (scheme == null) throw new NullPointerException("scheme == null");
    if (authParams == null) throw new NullPointerException("authParams == null");
    this.scheme = scheme;
    Map<String, String> newAuthParams = new LinkedHashMap<>();
    for (Entry<String, String> authParam : authParams.entrySet()) {
      String key = (authParam.getKey() == null) ? null : authParam.getKey().toLowerCase(US);
      newAuthParams.put(key, authParam.getValue());
    }
    this.authParams = unmodifiableMap(newAuthParams);
  }

  public Challenge(String scheme, String realm) {
    if (scheme == null) throw new NullPointerException("scheme == null");
    if (realm == null) throw new NullPointerException("realm == null");
    this.scheme = scheme;
    this.authParams = singletonMap("realm", realm);
  }

  /** Returns a copy of this charset that expects a credential encoded with {@code charset}. */
  public Challenge withCharset(Charset charset) {
    if (charset == null) throw new NullPointerException("charset == null");
    Map<String, String> authParams = new LinkedHashMap<>(this.authParams);
    authParams.put("charset", charset.name());
    return new Challenge(scheme, authParams);
  }

  /** Returns the authentication scheme, like {@code Basic}. */
  public String scheme() {
    return scheme;
  }

  /**
   * Returns the auth params, including {@code realm} and {@code charset} if present, but as
   * strings. The map's keys are lowercase and should be treated case-insensitively.
   */
  public Map<String, String> authParams() {
    return authParams;
  }

  /** Returns the protection space. */
  public String realm() {
    return authParams.get("realm");
  }

  /** Returns the charset that should be used to encode the credentials. */
  public Charset charset() {
    String charset = authParams.get("charset");
    if (charset != null) {
      try {
        return Charset.forName(charset);
      } catch (Exception ignore) {
      }
    }
    return ISO_8859_1;
  }

  @Override public boolean equals(@Nullable Object other) {
    return other instanceof Challenge
        && ((Challenge) other).scheme.equals(scheme)
        && ((Challenge) other).authParams.equals(authParams);
  }

  @Override public int hashCode() {
    int result = 29;
    result = 31 * result + scheme.hashCode();
    result = 31 * result + authParams.hashCode();
    return result;
  }

  @Override public String toString() {
    return scheme + " authParams=" + authParams;
  }
}
