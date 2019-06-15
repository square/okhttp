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

  /**
   * Returns the high-level media type, such as "text", "image", "audio", "video", or "application".
   */
  @get:JvmName("type") val type: String,

  /**
   * Returns a specific media subtype, such as "plain" or "png", "mpeg", "mp4" or "xml".
   */
  @get:JvmName("subtype") val subtype: String,

  private val charset: String?
) {

  /**
   * Returns the charset of this media type, or [defaultValue] if either this media type doesn't
   * specify a charset, of it its charset is unsupported by the current runtime.
   */
  @JvmOverloads
  fun charset(defaultValue: Charset? = null): Charset? {
    return try {
      if (charset != null) Charset.forName(charset) else defaultValue
    } catch (_: IllegalArgumentException) {
      defaultValue // This charset is invalid or unsupported. Give up.
    }
  }

  @JvmName("-deprecated_type")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "type"),
      level = DeprecationLevel.ERROR)
  fun type() = type

  @JvmName("-deprecated_subtype")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "subtype"),
      level = DeprecationLevel.ERROR)
  fun subtype() = subtype

  /**
   * Returns the encoded media type, like "text/plain; charset=utf-8", appropriate for use in a
   * Content-Type header.
   */
  override fun toString() = mediaType

  override fun equals(other: Any?) = other is MediaType && other.mediaType == mediaType

  override fun hashCode() = mediaType.hashCode()

  companion object {
    private const val TOKEN = "([a-zA-Z0-9-!#$%&'*+.^_`{|}~]+)"
    private const val QUOTED = "\"([^\"]*)\""
    private val TYPE_SUBTYPE = Pattern.compile("$TOKEN/$TOKEN")
    private val PARAMETER = Pattern.compile(";\\s*(?:$TOKEN=(?:$TOKEN|$QUOTED))?")

    /**
     * Returns a media type for this string.
     *
     * @throws IllegalArgumentException if this is not a well-formed media type.
     */
    @JvmStatic
    @JvmName("get")
    fun String.toMediaType(): MediaType {
      val typeSubtype = TYPE_SUBTYPE.matcher(this)
      require(typeSubtype.lookingAt()) { "No subtype found for: \"$this\"" }
      val type = typeSubtype.group(1).toLowerCase(Locale.US)
      val subtype = typeSubtype.group(2).toLowerCase(Locale.US)

      var charset: String? = null
      val parameter = PARAMETER.matcher(this)
      var s = typeSubtype.end()
      while (s < length) {
        parameter.region(s, length)
        require(parameter.lookingAt()) {
          "Parameter is not formatted correctly: \"${substring(s)}\" for: \"$this\""
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
          "Multiple charsets defined: \"$charset\" and: \"$charsetParameter\" for: \"$this\""
        }
        charset = charsetParameter
        s = parameter.end()
      }

      return MediaType(this, type, subtype, charset)
    }

    /** Returns a media type for this, or null if this is not a well-formed media type. */
    @JvmStatic
    @JvmName("parse")
    fun String.toMediaTypeOrNull(): MediaType? {
      return try {
        toMediaType()
      } catch (_: IllegalArgumentException) {
        null
      }
    }

    @JvmName("-deprecated_get")
    @Deprecated(
        message = "moved to extension function",
        replaceWith = ReplaceWith(
            expression = "mediaType.toMediaType()",
            imports = ["okhttp3.MediaType.Companion.toMediaType"]),
        level = DeprecationLevel.ERROR)
    fun get(mediaType: String): MediaType = mediaType.toMediaType()

    @JvmName("-deprecated_parse")
    @Deprecated(
        message = "moved to extension function",
        replaceWith = ReplaceWith(
            expression = "mediaType.toMediaTypeOrNull()",
            imports = ["okhttp3.MediaType.Companion.toMediaTypeOrNull"]),
        level = DeprecationLevel.ERROR)
    fun parse(mediaType: String): MediaType? = mediaType.toMediaTypeOrNull()
  }
}
