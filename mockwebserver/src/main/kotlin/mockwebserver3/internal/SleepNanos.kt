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
package mockwebserver3.internal

internal fun sleepNanos(nanos: Long) {
  val ms = nanos / 1_000_000L
  val ns = nanos - (ms * 1_000_000L)
  if (ms > 0L || nanos > 0) {
    Thread.sleep(ms, ns.toInt())
  }
}
