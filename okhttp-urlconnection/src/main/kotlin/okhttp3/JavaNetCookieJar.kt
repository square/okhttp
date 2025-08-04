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

import java.net.CookieHandler

/**
 * A cookie jar that delegates to a [java.net.CookieHandler].
 *
 * This implementation delegates everything to [okhttp3.java.net.cookiejar.JavaNetCookieJar], which
 * conforms to the package-naming limitations of JPMS.
 *
 * Callers should prefer to use [okhttp3.java.net.cookiejar.JavaNetCookieJar] directly.
 */
class JavaNetCookieJar private constructor(
  delegate: okhttp3.java.net.cookiejar.JavaNetCookieJar,
) : CookieJar by delegate {
  constructor(cookieHandler: CookieHandler) : this(
    okhttp3.java.net.cookiejar.JavaNetCookieJar(
      cookieHandler,
    ),
  )
}
