/*
 * Copyright (C) 2023 Square, Inc.
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
package okhttp3.internal.idn

import kotlin.math.abs
import okio.ByteString

internal sealed interface MappedRange {
  val rangeStart: Int

  data class Constant(
    override val rangeStart: Int,
    val type: Int,
  ) : MappedRange {
    val b1: Int
      get() =
        when (type) {
          TYPE_IGNORED -> 119
          TYPE_VALID -> 120
          TYPE_DISALLOWED -> 121
          else -> error("unexpected type: $type")
        }
  }

  data class Inline1(
    override val rangeStart: Int,
    private val mappedTo: ByteString,
  ) : MappedRange {
    val b1: Int
      get() {
        val b3bit8 = mappedTo[0] and 0x80 != 0
        return if (b3bit8) 123 else 122
      }

    val b2: Int
      get() = mappedTo[0] and 0x7f
  }

  data class Inline2(
    override val rangeStart: Int,
    private val mappedTo: ByteString,
  ) : MappedRange {
    val b1: Int
      get() {
        val b2bit8 = mappedTo[0] and 0x80 != 0
        val b3bit8 = mappedTo[1] and 0x80 != 0
        return when {
          b2bit8 && b3bit8 -> 127
          b3bit8 -> 126
          b2bit8 -> 125
          else -> 124
        }
      }

    val b2: Int
      get() = mappedTo[0] and 0x7f

    val b3: Int
      get() = mappedTo[1] and 0x7f
  }

  data class InlineDelta(
    override val rangeStart: Int,
    val codepointDelta: Int,
  ) : MappedRange {
    private val absoluteDelta = abs(codepointDelta)

    val b1: Int
      get() =
        when {
          codepointDelta < 0 -> 0x40 or (absoluteDelta shr 14)
          codepointDelta > 0 -> 0x50 or (absoluteDelta shr 14)
          else -> error("Unexpected codepointDelta of 0")
        }

    val b2: Int
      get() = absoluteDelta shr 7 and 0x7f

    val b3: Int
      get() = absoluteDelta and 0x7f

    companion object {
      const val MAX_VALUE = 0x3FFFF
    }
  }

  data class External(
    override val rangeStart: Int,
    val mappedTo: ByteString,
  ) : MappedRange
}
