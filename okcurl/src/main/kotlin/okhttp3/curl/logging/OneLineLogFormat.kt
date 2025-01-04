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
package okhttp3.curl.logging

import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField.HOUR_OF_DAY
import java.time.temporal.ChronoField.MINUTE_OF_HOUR
import java.time.temporal.ChronoField.NANO_OF_SECOND
import java.time.temporal.ChronoField.SECOND_OF_MINUTE
import java.util.logging.Formatter
import java.util.logging.LogRecord

/**
 * Is Java8 Data and Time really this bad, or is writing this on a plane from just javadocs a bad
 * idea?
 *
 * Why so much construction?
 */
class OneLineLogFormat : Formatter() {
  private val d =
    DateTimeFormatterBuilder()
      .appendValue(HOUR_OF_DAY, 2)
      .appendLiteral(':')
      .appendValue(MINUTE_OF_HOUR, 2)
      .optionalStart()
      .appendLiteral(':')
      .appendValue(SECOND_OF_MINUTE, 2)
      .optionalStart()
      .appendFraction(NANO_OF_SECOND, 3, 3, true)
      .toFormatter()

  private val offset = ZoneOffset.systemDefault()

  override fun format(record: LogRecord): String {
    val message = formatMessage(record)

    val time = Instant.ofEpochMilli(record.millis).atZone(offset)

    return if (record.thrown != null) {
      val sw = StringWriter(4096)
      val pw = PrintWriter(sw)
      record.thrown.printStackTrace(pw)
      String.format("%s\t%s%n%s%n", time.format(d), message, sw.toString())
    } else {
      String.format("%s\t%s%n", time.format(d), message)
    }
  }
}
