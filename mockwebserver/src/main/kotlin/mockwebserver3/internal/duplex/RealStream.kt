/*
 * Copyright (C) 2022 Block, Inc.
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
package mockwebserver3.internal.duplex

import mockwebserver3.Stream
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.Http2Stream
import okio.buffer

/** Adapt OkHttp's internal [Http2Stream] type to the public [Stream] type. */
internal class RealStream(
  private val http2Stream: Http2Stream,
) : Stream {
  override val requestBody = http2Stream.getSource().buffer()
  override val responseBody = http2Stream.getSink().buffer()

  override fun cancel() {
    http2Stream.closeLater(ErrorCode.CANCEL)
  }
}
