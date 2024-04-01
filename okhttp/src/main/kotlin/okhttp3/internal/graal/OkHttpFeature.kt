/*
 * Copyright (C) 2022 Square, Inc.
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

import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess

/**
 * Automatic configuration of OkHttp for native images.
 *
 * Currently, includes all necessary resources.
 */
class OkHttpFeature : Feature {
  @IgnoreJRERequirement
  override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess?) {
    RuntimeResourceAccess.addResource(
      ClassLoader.getSystemClassLoader().getUnnamedModule(),
      "okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz",
    )
  }
}
