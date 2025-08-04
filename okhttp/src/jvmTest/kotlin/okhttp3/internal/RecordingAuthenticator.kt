/*
 * Copyright (C) 2013 The Android Open Source Project
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
package okhttp3.internal

import java.net.Authenticator
import java.net.PasswordAuthentication

class RecordingAuthenticator(
  private val authentication: PasswordAuthentication? =
    PasswordAuthentication(
      "username",
      "password".toCharArray(),
    ),
) : Authenticator() {
  val calls = mutableListOf<String>()

  override fun getPasswordAuthentication(): PasswordAuthentication? {
    calls.add(
      "host=$requestingHost port=$requestingPort site=${requestingSite.hostName} " +
        "url=$requestingURL type=$requestorType prompt=$requestingPrompt " +
        "protocol=$requestingProtocol scheme=$requestingScheme",
    )
    return authentication
  }

  companion object {
    /** base64("username:password")  */
    const val BASE_64_CREDENTIALS = "dXNlcm5hbWU6cGFzc3dvcmQ="
  }
}
