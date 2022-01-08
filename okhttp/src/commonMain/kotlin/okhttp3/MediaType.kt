/*
 * Copyright (C) 2013 Square, Inc.
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

/**
 * An [RFC 2045][rfc_2045] Media Type, appropriate to describe the content type of an HTTP request
 * or response body.
 *
 * [rfc_2045]: http://tools.ietf.org/html/rfc2045
 */
expect class MediaType internal constructor(
  mediaType: String,
  type: String,
  subtype: String,
  parameterNamesAndValues: Array<String>,
) {
  internal val mediaType: String

  /**
   * Returns the high-level media type, such as "text", "image", "audio", "video", or "application".
   */
  val type: String

  /**
   * Returns a specific media subtype, such as "plain" or "png", "mpeg", "mp4" or "xml".
   */
  val subtype: String

  /** Alternating parameter names with their values, like `["charset", "utf-8"]`. */
  internal val parameterNamesAndValues: Array<String>

  /**
   * Returns the parameter [name] of this media type, or null if this media type does not define
   * such a parameter.
   */
  fun parameter(name: String): String?

  /**
   * Returns the encoded media type, like "text/plain; charset=utf-8", appropriate for use in a
   * Content-Type header.
   */
  override fun toString(): String

  companion object {
    /**
     * Returns a media type for this string.
     *
     * @throws IllegalArgumentException if this is not a well-formed media type.
     */
    fun String.toMediaType(): MediaType

    /** Returns a media type for this, or null if this is not a well-formed media type. */
    fun String.toMediaTypeOrNull(): MediaType?
  }
}
