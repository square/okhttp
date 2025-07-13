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
package okhttp3.internal.connection

/**
 * A policy for how the pool should treat a specific address.
 */
class AddressPolicy(
  /**
   * How many concurrent calls should be possible to make at any time.
   * The pool will routinely try to pre-emptively open connections to satisfy this minimum.
   * Connections will still be closed if they idle beyond the keep-alive but will be replaced.
   */
  @JvmField val minimumConcurrentCalls: Int = 0,
  /** How long to wait to retry pre-emptive connection attempts that fail. */
  @JvmField val backoffDelayMillis: Long = 60 * 1000,
  /** How much jitter to introduce in connection retry backoff delays */
  @JvmField val backoffJitterMillis: Int = 100,
)
