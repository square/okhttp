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
package okhttp3.internal.connection

/**
 * Used when we were unsuccessful in the planning phase of a connection:
 *
 *  * A DNS lookup failed
 *  * The configuration is incapable of carrying the request, such as when the client is configured
 *    to use `H2_PRIOR_KNOWLEDGE` but the URL's scheme is `https:`.
 *  * Preemptive proxy authentication failed.
 *
 * Planning failures are not necessarily fatal. For example, even if we can't DNS lookup the first
 * proxy in a list, looking up a subsequent one may succeed.
 */
internal class FailedPlan(e: Throwable) : RoutePlanner.Plan {
  val result = RoutePlanner.ConnectResult(plan = this, throwable = e)

  override val isReady = false

  override fun connectTcp() = result

  override fun connectTlsEtc() = result

  override fun handleSuccess() = error("unexpected call")

  override fun cancel() = error("unexpected cancel")

  override fun retry() = error("unexpected retry")
}
