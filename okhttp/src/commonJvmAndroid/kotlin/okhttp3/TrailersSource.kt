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

/**
 * Returns the trailers that follow an HTTP response, blocking if they aren't ready yet.
 * Implementations of this interface should respond to [Call.cancel] by immediately throwing an
 * [IOException].
 *
 * Most callers won't need this interface, and should use [Response.trailers] instead.
 *
 * This interface is for test and production code that creates [Response] instances without making
 * an HTTP call to a remote server.
 */
fun interface TrailersSource {
  @Throws(IOException::class)
  fun get(): Headers

  companion object {
    @JvmField
    val EMPTY: TrailersSource = TrailersSource { Headers.EMPTY }
  }
}
