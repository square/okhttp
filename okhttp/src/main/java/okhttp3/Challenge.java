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

import javax.annotation.Nullable;

/** An RFC 2617 challenge. */
public final class Challenge {
  private final String scheme;
  private final String realm;

  public Challenge(String scheme, String realm) {
    if (scheme == null) throw new NullPointerException("scheme == null");
    if (realm == null) throw new NullPointerException("realm == null");
    this.scheme = scheme;
    this.realm = realm;
  }

  /** Returns the authentication scheme, like {@code Basic}. */
  public String scheme() {
    return scheme;
  }

  /** Returns the protection space. */
  public String realm() {
    return realm;
  }

  @Override public boolean equals(@Nullable Object other) {
    return other instanceof Challenge
        && ((Challenge) other).scheme.equals(scheme)
        && ((Challenge) other).realm.equals(realm);
  }

  @Override public int hashCode() {
    int result = 29;
    result = 31 * result + realm.hashCode();
    result = 31 * result + scheme.hashCode();
    return result;
  }

  @Override public String toString() {
    return scheme + " realm=\"" + realm + "\"";
  }
}
