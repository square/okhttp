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
import java.net.Authenticator
import okhttp3.Authenticator.Companion.JAVA_NET_AUTHENTICATOR

/**
 * Adapts [Authenticator] to [okhttp3.Authenticator]. Configure OkHttp to use [Authenticator] with
 * [okhttp3.OkHttpClient.Builder.authenticator] or [okhttp3.OkHttpClient.Builder.proxyAuthenticator].
 */
class JavaNetAuthenticator : okhttp3.Authenticator {
  @Throws(IOException::class)
  override fun authenticate(route: Route?, response: Response): Request? =
    JAVA_NET_AUTHENTICATOR.authenticate(route, response)
}
