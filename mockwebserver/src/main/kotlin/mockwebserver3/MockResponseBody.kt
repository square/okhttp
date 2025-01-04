/*
 * Copyright (c) 2022 Block, Inc.
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
 *
 */
package mockwebserver3

import java.io.IOException
import okhttp3.ExperimentalOkHttpApi
import okio.BufferedSink

/**
 * The body of a [MockResponse].
 *
 * Unlike [okhttp3.ResponseBody], this interface is designed to be implemented by writers and not
 * called by readers.
 */
@ExperimentalOkHttpApi
interface MockResponseBody {
  /** The length of this response in bytes, or -1 if unknown. */
  val contentLength: Long

  @Throws(IOException::class)
  fun writeTo(sink: BufferedSink)
}
