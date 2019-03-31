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

import java.nio.charset.Charset
import java.util.Locale
import java.util.regex.Pattern

/**
 * An [RFC 2045][rfc_2045] Media Type, appropriate to describe the content type of an HTTP request
 * or response body.
 *
 * [rfc_2045]: http://tools.ietf.org/html/rfc2045
 */
class MediaType private constructor(
  private val mediaType: String,
  val type: String,
  val subtype: String,
  private val charset: String?
) {
  /**
   * Returns the charset of this media type, or `defaultValue` if either this media type
   * doesn't specify a charset, of it its charset is unsupported by the current runtime.
   */
  @JvmOverloads
  fun charset(defaultValue: Charset? = null): Charset? {
    try {
      return if (charset != null) Charset.forName(charset) else defaultValue
    } catch (e: IllegalArgumentException) {
      return defaultValue // This charset is invalid or unsupported. Give up.
    }
  }

  /**
   * Returns the high-level media type, such as "text", "image", "audio", "video", or
   * "application".
   */
  fun type() = type

  /**
   * Returns a specific media subtype, such as "plain" or "png", "mpeg", "mp4" or "xml".
   */
  fun subtype() = subtype

  /**
   * Returns the encoded media type, like "text/plain; charset=utf-8", appropriate for use in a
   * Content-Type header.
   */
  override fun toString(): String = mediaType

  override fun equals(other: Any?): Boolean = other is MediaType && other.mediaType == mediaType

  override fun hashCode(): Int = mediaType.hashCode()

  companion object {
    private const val TOKEN = "([a-zA-Z0-9-!#$%&'*+.^_`{|}~]+)"
    private const val QUOTED = "\"([^\"]*)\""
    private val TYPE_SUBTYPE = Pattern.compile("$TOKEN/$TOKEN")
    private val PARAMETER = Pattern.compile(";\\s*(?:$TOKEN=(?:$TOKEN|$QUOTED))?")

    /**
     * Returns a media type for `string`.
     *
     * @throws IllegalArgumentException if `string` is not a well-formed media type.
     */
    @JvmStatic
    fun get(string: String): MediaType {
      val typeSubtype = TYPE_SUBTYPE.matcher(string)
      require(typeSubtype.lookingAt()) { "No subtype found for: \"$string\"" }
      val type = typeSubtype.group(1).toLowerCase(Locale.US)
      val subtype = typeSubtype.group(2).toLowerCase(Locale.US)

      var charset: String? = null
      val parameter = PARAMETER.matcher(string)
      var s = typeSubtype.end()
      while (s < string.length) {
        parameter.region(s, string.length)
        require(parameter.lookingAt()) {
          "Parameter is not formatted correctly: \"${string.substring(s)}\" for: \"$string\""
        }

        val name = parameter.group(1)
        if (name == null || !name.equals("charset", ignoreCase = true)) {
          s = parameter.end()
          continue
        }
        val charsetParameter: String
        val token = parameter.group(2)
        charsetParameter = when {
          token == null -> {
            // Value is "double-quoted". That's valid and our regex group already strips the quotes.
            parameter.group(3)
          }
          token.startsWith("'") && token.endsWith("'") && token.length > 2 -> {
            // If the token is 'single-quoted' it's invalid! But we're lenient and strip the quotes.
            token.substring(1, token.length - 1)
          }
          else -> token
        }
        require(charset == null || charsetParameter.equals(charset, ignoreCase = true)) {
          "Multiple charsets defined: \"$charset\" and: \"$charsetParameter\" for: \"$string\""
        }
        charset = charsetParameter
        s = parameter.end()
      }

      return MediaType(string, type, subtype, charset)
    }

    /** Returns a media type for `string`, or null if `string` is not a well-formed media type. */
    @JvmStatic
    fun parse(string: String): MediaType? {
      try {
        return get(string)
      } catch (ignored: IllegalArgumentException) {
        return null
      }
    }
  }
}
