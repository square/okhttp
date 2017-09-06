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
import javax.annotation.Nullable;

import static okhttp3.internal.Util.ISO_8859_1;

/** An RFC 7617 challenge. */
public final class Challenge {
  private final String scheme;
  private final String realm;
  private final Charset charset;

  public Challenge(String scheme, String realm) {
    this(scheme, realm, ISO_8859_1);
  }

  private Challenge(String scheme, String realm, Charset charset) {
    if (scheme == null) throw new NullPointerException("scheme == null");
    if (realm == null) throw new NullPointerException("realm == null");
    if (charset == null) throw new NullPointerException("charset == null");
    this.scheme = scheme;
    this.realm = realm;
    this.charset = charset;
  }

  /** Returns a copy of this charset that expects a credential encoded with {@code charset}. */
  public Challenge withCharset(Charset charset) {
    return new Challenge(scheme, realm, charset);
  }

  /** Returns the authentication scheme, like {@code Basic}. */
  public String scheme() {
    return scheme;
  }

  /** Returns the protection space. */
  public String realm() {
    return realm;
  }

  /** Returns the charset that should be used to encode the credential. */
  public Charset charset() {
    return charset;
  }

  @Override public boolean equals(@Nullable Object other) {
    return other instanceof Challenge
        && ((Challenge) other).scheme.equals(scheme)
        && ((Challenge) other).realm.equals(realm)
        && ((Challenge) other).charset.equals(charset);
  }

  @Override public int hashCode() {
    int result = 29;
    result = 31 * result + realm.hashCode();
    result = 31 * result + scheme.hashCode();
    result = 31 * result + charset.hashCode();
    return result;
  }

  @Override public String toString() {
    return scheme
        + " realm=\"" + realm + "\""
        + " charset=\"" + charset + "\"";
  }
}
