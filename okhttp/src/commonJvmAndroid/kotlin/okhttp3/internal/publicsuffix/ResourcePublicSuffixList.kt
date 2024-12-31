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

import okio.FileSystem
import okio.GzipSource
import okio.Path
import okio.Path.Companion.toPath
import okio.Source

internal class ResourcePublicSuffixList(
  override val path: Path = PUBLIC_SUFFIX_RESOURCE,
  val fileSystem: FileSystem = FileSystem.Companion.RESOURCES,
) : BasePublicSuffixList() {
  override fun listSource(): Source = GzipSource(fileSystem.source(path))

  companion object {
    @JvmField
    val PUBLIC_SUFFIX_RESOURCE =
      "okhttp3/internal/publicsuffix/${PublicSuffixDatabase::class.java.simpleName}.gz".toPath()
  }
}
