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
@file:Suppress("ktlint:standard:filename")

package okhttp3.internal

import okhttp3.MediaType

internal fun MediaType.commonParameter(name: String): String? {
  for (i in parameterNamesAndValues.indices step 2) {
    if (parameterNamesAndValues[i].equals(name, ignoreCase = true)) {
      return parameterNamesAndValues[i + 1]
    }
  }
  return null
}

internal fun MediaType.commonEquals(other: Any?): Boolean = other is MediaType && other.mediaType == mediaType

internal fun MediaType.commonToString(): String = mediaType

internal fun MediaType.commonHashCode(): Int = mediaType.hashCode()

private const val TOKEN = "([a-zA-Z0-9-!#$%&'*+.^_`{|}~]+)"
private const val QUOTED = "\"([^\"]*)\""
private val TYPE_SUBTYPE = Regex("$TOKEN/$TOKEN")
private val PARAMETER = Regex(";\\s*(?:$TOKEN=(?:$TOKEN|$QUOTED))?")

/**
 * Returns a media type for this string.
 *
 * @throws IllegalArgumentException if this is not a well-formed media type.
 */
internal fun String.commonToMediaType(): MediaType {
  val typeSubtype: MatchResult =
    TYPE_SUBTYPE.matchAtPolyfill(this, 0)
      ?: throw IllegalArgumentException("No subtype found for: \"$this\"")
  val type = typeSubtype.groupValues[1].lowercase()
  val subtype = typeSubtype.groupValues[2].lowercase()

  val parameterNamesAndValues = mutableListOf<String>()
  var s = typeSubtype.range.last + 1
  while (s < length) {
    val parameter = PARAMETER.matchAtPolyfill(this, s)
    require(parameter != null) {
      "Parameter is not formatted correctly: \"${substring(s)}\" for: \"$this\""
    }

    val name = parameter.groups[1]?.value
    if (name == null) {
      s = parameter.range.last + 1
      continue
    }

    val token = parameter.groups[2]?.value
    val value =
      when {
        token == null -> {
          // Value is "double-quoted". That's valid and our regex group already strips the quotes.
          parameter.groups[3]!!.value
        }
        token.startsWith("'") && token.endsWith("'") && token.length > 2 -> {
          // If the token is 'single-quoted' it's invalid! But we're lenient and strip the quotes.
          token.substring(1, token.length - 1)
        }
        else -> token
      }

    parameterNamesAndValues += name
    parameterNamesAndValues += value
    s = parameter.range.last + 1
  }

  return MediaType(this, type, subtype, parameterNamesAndValues.toTypedArray())
}

/** Returns a media type for this, or null if this is not a well-formed media type. */
fun String.commonToMediaTypeOrNull(): MediaType? {
  return try {
    commonToMediaType()
  } catch (_: IllegalArgumentException) {
    null
  }
}
