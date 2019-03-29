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
package okhttp3

import okio.ByteString.Companion.encode
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.ISO_8859_1

/** Factory for HTTP authorization credentials. */
object Credentials {
  /** Returns an auth credential for the Basic scheme. */
  @JvmStatic @JvmOverloads fun basic(
    username: String,
    password: String,
    charset: Charset = ISO_8859_1
  ): String {
    val usernameAndPassword = "$username:$password"
    val encoded = usernameAndPassword.encode(charset).base64()
    return "Basic $encoded"
  }
}
