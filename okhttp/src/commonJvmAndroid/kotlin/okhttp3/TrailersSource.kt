/*
 * Copyright (C) 2025 Square, Inc.
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

import okio.IOException

@ExperimentalOkHttpApi
fun interface TrailersSource {
  /** Returns the trailers, blocking if they need to be loaded. */
  @Throws(IOException::class)
  fun get(): Headers

  companion object {
    @JvmField
    val EMPTY: TrailersSource = TrailersSource { Headers.EMPTY }
  }
}
