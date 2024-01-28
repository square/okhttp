/*
 * Copyright (C) 2014 Square, Inc.
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
import okhttp3.Headers

/** An HTTP request initiated by the server. */
@ExperimentalOkHttpApi
class PushPromise(
  val method: String,
  val path: String,
  val headers: Headers,
  val response: MockResponse,
)
