/*
 * Copyright (C) 2014 Square, Inc.
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

import okhttp3.HttpUrl.Companion.FORM_ENCODE_SET
import okhttp3.HttpUrl.Companion.canonicalize
import okhttp3.HttpUrl.Companion.percentDecode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.internal.toImmutableList
import okio.Buffer
import okio.BufferedSink
import java.io.IOException
import java.nio.charset.Charset

class FormBody internal constructor(
  encodedNames: List<String>,
  encodedValues: List<String>
) : RequestBody() {
  private val encodedNames: List<String> = encodedNames.toImmutableList()
  private val encodedValues: List<String> = encodedValues.toImmutableList()

  /** The number of key-value pairs in this form-encoded body. */
  @get:JvmName("size") val size: Int
    get() = encodedNames.size

  @JvmName("-deprecated_size")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "size"),
      level = DeprecationLevel.ERROR)
  fun size(): Int = size

  fun encodedName(index: Int) = encodedNames[index]

  fun name(index: Int) = encodedName(index).percentDecode(plusIsSpace = true)

  fun encodedValue(index: Int) = encodedValues[index]

  fun value(index: Int) = encodedValue(index).percentDecode(plusIsSpace = true)

  override fun contentType() = CONTENT_TYPE

  override fun contentLength() = writeOrCountBytes(null, true)

  @Throws(IOException::class)
  override fun writeTo(sink: BufferedSink) {
    writeOrCountBytes(sink, false)
  }

  /**
   * Either writes this request to [sink] or measures its content length. We have one method
   * do double-duty to make sure the counting and content are consistent, particularly when it comes
   * to awkward operations like measuring the encoded length of header strings, or the
   * length-in-digits of an encoded integer.
   */
  private fun writeOrCountBytes(sink: BufferedSink?, countBytes: Boolean): Long {
    var byteCount = 0L
    val buffer: Buffer = if (countBytes) Buffer() else sink!!.buffer

    for (i in 0 until encodedNames.size) {
      if (i > 0) buffer.writeByte('&'.toInt())
      buffer.writeUtf8(encodedNames[i])
      buffer.writeByte('='.toInt())
      buffer.writeUtf8(encodedValues[i])
    }

    if (countBytes) {
      byteCount = buffer.size
      buffer.clear()
    }

    return byteCount
  }

  class Builder @JvmOverloads constructor(private val charset: Charset? = null) {
    private val names = mutableListOf<String>()
    private val values = mutableListOf<String>()

    fun add(name: String, value: String) = apply {
      names += name.canonicalize(
          encodeSet = FORM_ENCODE_SET,
          plusIsSpace = true,
          charset = charset
      )
      values += value.canonicalize(
          encodeSet = FORM_ENCODE_SET,
          plusIsSpace = true,
          charset = charset
      )
    }

    fun addEncoded(name: String, value: String) = apply {
      names += name.canonicalize(
          encodeSet = FORM_ENCODE_SET,
          alreadyEncoded = true,
          plusIsSpace = true,
          charset = charset
      )
      values += value.canonicalize(
          encodeSet = FORM_ENCODE_SET,
          alreadyEncoded = true,
          plusIsSpace = true,
          charset = charset
      )
    }

    fun build(): FormBody = FormBody(names, values)
  }

  companion object {
    private val CONTENT_TYPE: MediaType = "application/x-www-form-urlencoded".toMediaType()
  }
}
