/*
 * Copyright (C) 2013 Square, Inc.
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
import okhttp3.Authenticator.Companion.JAVA_NET_AUTHENTICATOR

/**
 * Do not use this.
 *
 * Instead, configure your OkHttpClient.Builder to use `Authenticator.JAVA_NET_AUTHENTICATOR`:
 *
 * ```
 *   val okHttpClient = OkHttpClient.Builder()
 *     .authenticator(okhttp3.Authenticator.Companion.JAVA_NET_AUTHENTICATOR)
 *     .build()
 * ```
 */
@Deprecated(message = "Use okhttp3.Authenticator.Companion.JAVA_NET_AUTHENTICATOR instead")
class JavaNetAuthenticator : Authenticator {
  @Throws(IOException::class)
  override fun authenticate(route: Route?, response: Response): Request? =
    JAVA_NET_AUTHENTICATOR.authenticate(route, response)
}
