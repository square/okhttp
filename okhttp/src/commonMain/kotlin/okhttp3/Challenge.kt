/*
 * Copyright (c) 2022 Square, Inc.
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

package okhttp3

/**
 * An [RFC 7235][rfc_7235] challenge.
 *
 * [rfc_7235]: https://tools.ietf.org/html/rfc7235
 */
expect class Challenge {

  /** Returns the authentication scheme, like `Basic`. */
  val scheme: String

  /**
   * Returns the auth params, including [realm] and [charset] if present, but as
   * strings. The map's keys are lowercase and should be treated case-insensitively.
   */
  val authParams: Map<String?, String>

  /** Returns the protection space. */
  val realm: String?

  constructor(scheme: String, realm: String)
  constructor(scheme: String, authParams: Map<String?, String>)

  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int

  override fun toString(): String
}
