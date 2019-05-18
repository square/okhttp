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

import okhttp3.internal.toImmutableList
import okio.Buffer
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.UUID

/**
 * An [RFC 2387][rfc_2387]-compliant request body.
 *
 * [rfc_2387]: http://www.ietf.org/rfc/rfc2387.txt
 */
class MultipartBody internal constructor(
  private val boundary: ByteString,
  private val originalType: MediaType,
  parts: List<Part>
) : RequestBody() {
  private val contentType: MediaType = MediaType.get("$originalType; boundary=${boundary.utf8()}")
  private val parts: List<Part> = parts.toImmutableList()
  private var contentLength = -1L

  fun type(): MediaType = originalType

  fun boundary(): String = boundary.utf8()

  /** The number of parts in this multipart body.  */
  fun size(): Int = parts.size

  fun parts(): List<Part> = parts

  fun part(index: Int): Part = parts[index]

  /** A combination of [type] and [boundary].  */
  override fun contentType(): MediaType = contentType

  @Throws(IOException::class)
  override fun contentLength(): Long {
    var result = contentLength
    if (result == -1L) {
      result = writeOrCountBytes(null, true)
      contentLength = result
    }
    return result
  }

  @Throws(IOException::class)
  override fun writeTo(sink: BufferedSink) {
    writeOrCountBytes(sink, false)
  }

  /**
   * Either writes this request to [sink] or measures its content length. We have one method do
   * double-duty to make sure the counting and content are consistent, particularly when it comes
   * to awkward operations like measuring the encoded length of header strings, or the
   * length-in-digits of an encoded integer.
   */
  @Throws(IOException::class)
  private fun writeOrCountBytes(
    sink: BufferedSink?,
    countBytes: Boolean
  ): Long {
    var sink = sink
    var byteCount = 0L

    var byteCountBuffer: Buffer? = null
    if (countBytes) {
      byteCountBuffer = Buffer()
      sink = byteCountBuffer
    }

    for (p in 0 until parts.size) {
      val part = parts[p]
      val headers = part.headers
      val body = part.body

      sink!!.write(DASHDASH)
      sink.write(boundary)
      sink.write(CRLF)

      if (headers != null) {
        for (h in 0 until headers.size) {
          sink.writeUtf8(headers.name(h))
              .write(COLONSPACE)
              .writeUtf8(headers.value(h))
              .write(CRLF)
        }
      }

      val contentType = body.contentType()
      if (contentType != null) {
        sink.writeUtf8("Content-Type: ")
            .writeUtf8(contentType.toString())
            .write(CRLF)
      }

      val contentLength = body.contentLength()
      if (contentLength != -1L) {
        sink.writeUtf8("Content-Length: ")
            .writeDecimalLong(contentLength)
            .write(CRLF)
      } else if (countBytes) {
        // We can't measure the body's size without the sizes of its components.
        byteCountBuffer!!.clear()
        return -1L
      }

      sink.write(CRLF)

      if (countBytes) {
        byteCount += contentLength
      } else {
        body.writeTo(sink)
      }

      sink.write(CRLF)
    }

    sink!!.write(DASHDASH)
    sink.write(boundary)
    sink.write(DASHDASH)
    sink.write(CRLF)

    if (countBytes) {
      byteCount += byteCountBuffer!!.size
      byteCountBuffer.clear()
    }

    return byteCount
  }

  class Part private constructor(internal val headers: Headers?, internal val body: RequestBody) {

    fun headers(): Headers? = headers

    fun body(): RequestBody = body

    companion object {
      @JvmStatic
      fun create(body: RequestBody): Part = create(null, body)

      @JvmStatic
      fun create(headers: Headers?, body: RequestBody): Part {
        require(headers?.get("Content-Type") == null) { "Unexpected header: Content-Type" }
        require(headers?.get("Content-Length") == null) { "Unexpected header: Content-Length" }
        return Part(headers, body)
      }

      @JvmStatic
      fun createFormData(name: String, value: String): Part =
          createFormData(name, null, create(null, value))

      @JvmStatic
      fun createFormData(name: String, filename: String?, body: RequestBody): Part {
        val disposition = StringBuilder("form-data; name=")
        disposition.appendQuotedString(name)

        if (filename != null) {
          disposition.append("; filename=")
          disposition.appendQuotedString(filename)
        }

        val headers = Headers.Builder()
            .addUnsafeNonAscii("Content-Disposition", disposition.toString())
            .build()

        return create(headers, body)
      }
    }
  }

  class Builder @JvmOverloads constructor(boundary: String = UUID.randomUUID().toString()) {
    private val boundary: ByteString = boundary.encodeUtf8()
    private var type = MIXED
    private val parts = mutableListOf<Part>()

    /**
     * Set the MIME type. Expected values for `type` are [MIXED] (the default), [ALTERNATIVE],
     * [DIGEST], [PARALLEL] and [FORM].
     */
    fun setType(type: MediaType) = apply {
      require(type.type == "multipart") { "multipart != $type" }
      this.type = type
    }

    /** Add a part to the body.  */
    fun addPart(body: RequestBody) = apply {
      addPart(Part.create(body))
    }

    /** Add a part to the body.  */
    fun addPart(headers: Headers?, body: RequestBody) = apply {
      addPart(Part.create(headers, body))
    }

    /** Add a form data part to the body.  */
    fun addFormDataPart(name: String, value: String) = apply {
      addPart(Part.createFormData(name, value))
    }

    /** Add a form data part to the body.  */
    fun addFormDataPart(name: String, filename: String?, body: RequestBody) = apply {
      addPart(Part.createFormData(name, filename, body))
    }

    /** Add a part to the body.  */
    fun addPart(part: Part) = apply {
      parts.add(part)
    }

    /** Assemble the specified parts into a request body.  */
    fun build(): MultipartBody {
      check(parts.isNotEmpty()) { "Multipart body must have at least one part." }
      return MultipartBody(boundary, type, parts)
    }
  }

  companion object {
    /**
     * The "mixed" subtype of "multipart" is intended for use when the body parts are independent
     * and need to be bundled in a particular order. Any "multipart" subtypes that an implementation
     * does not recognize must be treated as being of subtype "mixed".
     */
    @JvmField
    val MIXED = MediaType.get("multipart/mixed")

    /**
     * The "multipart/alternative" type is syntactically identical to "multipart/mixed", but the
     * semantics are different. In particular, each of the body parts is an "alternative" version of
     * the same information.
     */
    @JvmField
    val ALTERNATIVE = MediaType.get("multipart/alternative")

    /**
     * This type is syntactically identical to "multipart/mixed", but the semantics are different.
     * In particular, in a digest, the default `Content-Type` value for a body part is changed from
     * "text/plain" to "message/rfc822".
     */
    @JvmField
    val DIGEST = MediaType.get("multipart/digest")

    /**
     * This type is syntactically identical to "multipart/mixed", but the semantics are different.
     * In particular, in a parallel entity, the order of body parts is not significant.
     */
    @JvmField
    val PARALLEL = MediaType.get("multipart/parallel")

    /**
     * The media-type multipart/form-data follows the rules of all multipart MIME data streams as
     * outlined in RFC 2046. In forms, there are a series of fields to be supplied by the user who
     * fills out the form. Each field has a name. Within a given form, the names are unique.
     */
    @JvmField
    val FORM = MediaType.get("multipart/form-data")

    private val COLONSPACE = byteArrayOf(':'.toByte(), ' '.toByte())
    private val CRLF = byteArrayOf('\r'.toByte(), '\n'.toByte())
    private val DASHDASH = byteArrayOf('-'.toByte(), '-'.toByte())

    /**
     * Appends a quoted-string to a StringBuilder.
     *
     * RFC 2388 is rather vague about how one should escape special characters in form-data
     * parameters, and as it turns out Firefox and Chrome actually do rather different things, and
     * both say in their comments that they're not really sure what the right approach is. We go
     * with Chrome's behavior (which also experimentally seems to match what IE does), but if you
     * actually want to have a good chance of things working, please avoid double-quotes, newlines,
     * percent signs, and the like in your field names.
     */
    internal fun StringBuilder.appendQuotedString(key: String) {
      append('"')
      for (i in 0 until key.length) {
        when (val ch = key[i]) {
          '\n' -> append("%0A")
          '\r' -> append("%0D")
          '"' -> append("%22")
          else -> append(ch)
        }
      }
      append('"')
    }
  }
}
