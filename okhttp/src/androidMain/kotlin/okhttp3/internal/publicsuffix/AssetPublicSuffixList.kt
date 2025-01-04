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
package okhttp3.internal.publicsuffix

import java.io.IOException
import okhttp3.internal.platform.PlatformRegistry
import okio.Source
import okio.source

internal class AssetPublicSuffixList(
  override val path: String = PUBLIC_SUFFIX_RESOURCE,
) : BasePublicSuffixList() {
  override fun listSource(): Source {
    val assets =
      PlatformRegistry.applicationContext?.assets ?: throw IOException("Platform applicationContext not initialized")

    return assets.open(path).source()
  }

  companion object {
    val PUBLIC_SUFFIX_RESOURCE = "PublicSuffixDatabase.list"
  }
}
