/*
 * Copyright (C) 2024 Block, Inc.
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
package okhttp3.internal.platform

import java.security.Security

actual object PlatformRegistry {
  private val isConscryptPreferred: Boolean
    get() {
      val preferredProvider = Security.getProviders()[0].name
      return "Conscrypt" == preferredProvider
    }

  private val isOpenJSSEPreferred: Boolean
    get() {
      val preferredProvider = Security.getProviders()[0].name
      return "OpenJSSE" == preferredProvider
    }

  private val isBouncyCastlePreferred: Boolean
    get() {
      val preferredProvider = Security.getProviders()[0].name
      return "BC" == preferredProvider
    }

  actual fun findPlatform(): Platform {
    if (isConscryptPreferred) {
      val conscrypt = ConscryptPlatform.buildIfSupported()

      if (conscrypt != null) {
        return conscrypt
      }
    }

    if (isBouncyCastlePreferred) {
      val bc = BouncyCastlePlatform.buildIfSupported()

      if (bc != null) {
        return bc
      }
    }

    if (isOpenJSSEPreferred) {
      val openJSSE = OpenJSSEPlatform.buildIfSupported()

      if (openJSSE != null) {
        return openJSSE
      }
    }

    // An Oracle JDK 9 like OpenJDK, or JDK 8 251+.
    val jdk9 = Jdk9Platform.buildIfSupported()

    if (jdk9 != null) {
      return jdk9
    }

    // An Oracle JDK 8 like OpenJDK, pre 251.
    val jdkWithJettyBoot = Jdk8WithJettyBootPlatform.buildIfSupported()

    if (jdkWithJettyBoot != null) {
      return jdkWithJettyBoot
    }

    return Platform()
  }

  actual val isAndroid: Boolean
    get() = false
}
