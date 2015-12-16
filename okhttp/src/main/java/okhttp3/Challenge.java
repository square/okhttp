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

import okhttp3.internal.Util;

/** An RFC 2617 challenge. */
public final class Challenge {
  private final String scheme;
  private final String realm;

  public Challenge(String scheme, String realm) {
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

  @Override public boolean equals(Object o) {
    return o instanceof Challenge
        && Util.equal(scheme, ((Challenge) o).scheme)
        && Util.equal(realm, ((Challenge) o).realm);
  }

  @Override public int hashCode() {
    int result = 29;
    result = 31 * result + (realm != null ? realm.hashCode() : 0);
    result = 31 * result + (scheme != null ? scheme.hashCode() : 0);
    return result;
  }

  @Override public String toString() {
    return scheme + " realm=\"" + realm + "\"";
  }
}
