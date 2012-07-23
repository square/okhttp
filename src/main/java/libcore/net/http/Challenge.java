/*
 * Copyright (C) 2011 The Android Open Source Project
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

package libcore.net.http;

/**
 * An RFC 2617 challenge.
 */
final class Challenge {
    final String scheme;
    final String realm;

    Challenge(String scheme, String realm) {
        this.scheme = scheme;
        this.realm = realm;
    }

    @Override public boolean equals(Object o) {
        return o instanceof Challenge
                && ((Challenge) o).scheme.equals(scheme)
                && ((Challenge) o).realm.equals(realm);
    }

    @Override public int hashCode() {
        return scheme.hashCode() + 31 * realm.hashCode();
    }
}
