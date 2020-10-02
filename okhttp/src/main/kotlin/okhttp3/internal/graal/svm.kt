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
package okhttp3.internal.graal

import com.oracle.svm.core.annotate.Delete
import com.oracle.svm.core.annotate.Substitute
import com.oracle.svm.core.annotate.TargetClass
import okhttp3.internal.platform.Android10Platform
import okhttp3.internal.platform.AndroidPlatform
import okhttp3.internal.platform.BouncyCastlePlatform
import okhttp3.internal.platform.ConscryptPlatform
import okhttp3.internal.platform.Jdk8WithJettyBootPlatform
import okhttp3.internal.platform.Jdk9Platform
import okhttp3.internal.platform.OpenJSSEPlatform
import okhttp3.internal.platform.Platform

@TargetClass(AndroidPlatform::class)
@Delete
class TargetAndroidPlatform {
}

@TargetClass(Android10Platform::class)
@Delete
class TargetAndroid10Platform {
}

@TargetClass(BouncyCastlePlatform::class)
@Delete
class TargetBouncyCastlePlatform {
}

@TargetClass(ConscryptPlatform::class)
@Delete
class TargetConscryptPlatform {
}

@TargetClass(Jdk8WithJettyBootPlatform::class)
@Delete
class TargetJdk8WithJettyBootPlatform {
}

@TargetClass(OpenJSSEPlatform::class)
@Delete
class TargetOpenJSSEPlatform {
}

@TargetClass(Platform.Companion::class)
class TargetPlatform {
  @Substitute
  fun findPlatform(): Platform {
    return Jdk9Platform.buildIfSupported()!!
  }
}