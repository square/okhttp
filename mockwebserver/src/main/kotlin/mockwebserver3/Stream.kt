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
package mockwebserver3

import okhttp3.ExperimentalOkHttpApi
import okio.BufferedSink
import okio.BufferedSource

/**
 * A bidirectional sequence of data frames exchanged between client and server.
 */
@ExperimentalOkHttpApi
interface Stream {
  val requestBody: BufferedSource
  val responseBody: BufferedSink

  /**
   * Terminate the stream so that no further data is transmitted or received. Note that
   * [requestBody] may return data after this call; that is the buffered data received before this
   * stream was canceled.
   *
   * This does nothing if [requestBody] and [responseBody] are already closed.
   *
   * For HTTP/2 this sends the [CANCEL](https://datatracker.ietf.org/doc/html/rfc7540#section-7)
   * error code.
   */
  fun cancel()
}
