/*
 * Copyright (C) 2024 Square, Inc.
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

import okio.Timeout

open class FailingCall : Call {
  override fun request(): Request = error("unexpected")

  override fun execute(): Response = error("unexpected")

  override fun enqueue(responseCallback: Callback): Unit = error("unexpected")

  override fun cancel(): Unit = error("unexpected")

  override fun isExecuted(): Boolean = error("unexpected")

  override fun isCanceled(): Boolean = error("unexpected")

  override fun timeout(): Timeout = error("unexpected")

  override fun clone(): Call = error("unexpected")
}
