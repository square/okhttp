/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.internal.authenticator

import java.net.Authenticator
import java.net.InetAddress
import junit.framework.TestCase.assertNull
import okhttp3.FakeDns
import okhttp3.Protocol.HTTP_2
import okhttp3.Request
import okhttp3.Response
import okhttp3.TestValueFactory
import okhttp3.internal.RecordingAuthenticator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

// Most tests from URLConnectionTest
class JavaNetAuthenticatorTest {
  private var authenticator = JavaNetAuthenticator()
  private val fakeDns = FakeDns()
  private val recordingAuthenticator = RecordingAuthenticator()
  private val factory =
    TestValueFactory()
      .apply {
        dns = fakeDns
      }

  @BeforeEach
  fun setup() {
    Authenticator.setDefault(recordingAuthenticator)
  }

  @AfterEach
  fun tearDown() {
    Authenticator.setDefault(null)
    factory.close()
  }

  @Test
  fun testBasicAuth() {
    fakeDns["server"] = listOf(InetAddress.getLocalHost())

    val route = factory.newRoute()

    val request =
      Request.Builder()
        .url("https://server/robots.txt")
        .build()
    val response =
      Response.Builder()
        .request(request)
        .code(401)
        .header("WWW-Authenticate", "Basic realm=\"User Visible Realm\"")
        .protocol(HTTP_2)
        .message("Unauthorized")
        .build()
    val authRequest = authenticator.authenticate(route, response)

    assertEquals(
      "Basic ${RecordingAuthenticator.BASE_64_CREDENTIALS}",
      authRequest!!.header("Authorization"),
    )
  }

  @Test
  fun noSupportForNonBasicAuth() {
    val request =
      Request.Builder()
        .url("https://server/robots.txt")
        .build()

    val response =
      Response.Builder()
        .request(request)
        .code(401)
        .header("WWW-Authenticate", "UnsupportedScheme realm=\"User Visible Realm\"")
        .protocol(HTTP_2)
        .message("Unauthorized")
        .build()

    val authRequest = authenticator.authenticate(null, response)
    assertNull(authRequest)
  }
}
