/*
 * Copyright (C) 2016 Square, Inc.
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

object PlatformRegistry {
  @Volatile internal var platform = findPlatform()

  fun get(): Platform = platform

  fun resetForTests(platform: Platform = findPlatform()) {
    this.platform = platform
  }

  /** Attempt to match the host runtime to a capable Platform implementation.  */
  @JvmStatic
  private fun findPlatform(): Platform {
    val android = AndroidPlatform.buildIfSupported()

    if (android != null) {
      return android
    }

    if (ConscryptPlatform.isConscryptPreferred) {
      val conscrypt = ConscryptPlatform.buildIfSupported()

      if (conscrypt != null) {
        return conscrypt
      }
    }

    val jdk9 = Jdk9Platform.buildIfSupported()

    if (jdk9 != null) {
      return jdk9
    }

    // An Oracle JDK 8 like OpenJDK.
    val jdkWithJettyBoot = Jdk8WithJettyBootPlatform.buildIfSupported()

    return jdkWithJettyBoot ?: Platform()
  }
}