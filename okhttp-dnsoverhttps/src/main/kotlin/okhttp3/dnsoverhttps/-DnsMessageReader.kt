/*
 * Copyright (c) 2026 OkHttp Authors
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

package okhttp3.dnsoverhttps

import java.io.IOException
import okio.BufferedSource
import okio.ByteString
import okio.ProtocolException

/**
 * https://datatracker.ietf.org/doc/html/rfc1035
 */
internal class DnsMessageReader(
  source: BufferedSource,
) {
  /**
   * Keep a reference to the source at its initial offset, so we can implement name compression.
   * That mechanism requires us to backtrack in the message.
   */
  private val sourceOffsetZero = source
  private val source = source.peek()

  fun read(): DnsMessage {
    val id = source.readShort()
    val flags = source.readShort().toInt()

    val questionCount = source.readShort().toUShort().toInt()
    val answerCount = source.readShort().toUShort().toInt()
    val nameServerCount = source.readShort().toUShort().toInt()
    val additionalRecordCount = source.readShort().toUShort().toInt()

    val questions = mutableListOf<Question>()
    for (i in 0 until questionCount) {
      questions += readQuestion()
    }

    val answers = mutableListOf<ResourceRecord>()
    for (i in 0 until answerCount) {
      answers += readResourceRecord() ?: continue
    }

    val authorityRecords = mutableListOf<ResourceRecord>()
    for (i in 0 until nameServerCount) {
      authorityRecords += readResourceRecord() ?: continue
    }

    val additionalRecords = mutableListOf<ResourceRecord>()
    for (i in 0 until additionalRecordCount) {
      additionalRecords += readResourceRecord() ?: continue
    }

    if (!source.exhausted()) {
      throw ProtocolException("unexpected trailing data in message")
    }

    sourceOffsetZero.skipAll()

    return DnsMessage(
      id = id,
      flags = flags,
      questions = questions,
      answers = answers,
      authorityRecords = authorityRecords,
      additionalRecords = additionalRecords,
    )
  }

  private fun readQuestion(): Question {
    val name = readName()
    val type = source.readShort()
    val `class` = source.readShort()
    return Question(
      name = name,
      type = type,
      `class` = `class`,
    )
  }

  private fun readName(): String {
    return buildString {
      readName(source, this)
    }
  }

  private tailrec fun readName(source: BufferedSource, builder: StringBuilder) {
    while (true) {
      val labelTypeAndLength = source.readByte().toUByte().toInt()
      val labelType = labelTypeAndLength and 0b11000000
      val labelLength = labelTypeAndLength and 0b00111111
      when (labelType) {
        // Inline value.
        0b00_000000 -> {
          if (labelLength == 0) return
          if (builder.isNotEmpty()) builder.append('.')
          builder.append(source.readUtf8(labelLength.toLong()))
        }

        // Compressed suffix.
        0b11_000000 -> {
          val offsetLength = (labelLength shl 8) or source.readByte().toUByte().toInt()
          val offsetSource = sourceOffsetZero.peek()
          offsetSource.skip(offsetLength.toLong())
          return readName(offsetSource, builder)
        }

        0b01_000000, 0b10_000000 -> throw ProtocolException("unsupported label type")
      }
    }
  }

  /** Returns null if this record type is unsupported. */
  fun readResourceRecord(): ResourceRecord? {
    val name = readName()
    val type = source.readShort().toInt()
    val `class` = source.readShort().toInt()
    val timeToLive = source.readInt()
    val recordDataLength = source.readShort().toLong()

    when {
      `class` == CLASS_IN && type == TYPE_A -> {
        if (recordDataLength != 4L) throw ProtocolException("unexpected record length")
        val address = source.readByteString(4L)
        return ResourceRecord.IpAddress(
          name = name,
          timeToLive = timeToLive,
          address = address,
        )
      }

      `class` == CLASS_IN && type == TYPE_AAAA -> {
        if (recordDataLength != 16L) throw ProtocolException("unexpected record length")
        val address = source.readByteString(16L)
        return ResourceRecord.IpAddress(
          name = name,
          timeToLive = timeToLive,
          address = address,
        )
      }

      else -> {
        source.skip(recordDataLength)
        return null
      }
    }
  }

  data class DnsMessage(
    val id: Short,
    val flags: Int,
    val questions: List<Question>,
    val answers: List<ResourceRecord>,
    val authorityRecords: List<ResourceRecord> = listOf(),
    val additionalRecords: List<ResourceRecord> = listOf(),
  ) {
    val responseCode: Int
      get() = (flags and 0b0000_0000_0000_1111)
  }

  data class Question(
    val name: String,
    val type: Short,
    val `class`: Short,
  )

  sealed interface ResourceRecord {
    val name: String
    val timeToLive: Int

    data class IpAddress(
      override val name: String,
      override val timeToLive: Int,
      val address: ByteString,
    ) : ResourceRecord
  }
}

internal val TYPE_A = 1
internal val TYPE_AAAA = 28

internal val CLASS_IN = 1

internal val RESPONSE_CODE_SUCCESS = 0
internal val RESPONSE_CODE_SERVER_FAILURE = 2

@Throws(IOException::class)
internal fun BufferedSource.skipAll() {
  while (!exhausted()) {
    skip(buffer.size)
  }
}
