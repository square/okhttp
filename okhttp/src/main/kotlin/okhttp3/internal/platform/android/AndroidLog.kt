/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.internal.platform.android

import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.reflect.KClass
import okhttp3.OkHttpClient
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.http2.Http2

private val LogRecord.androidLevel: Int
  get() = when {
    level.intValue() > Level.INFO.intValue() -> Log.WARN
    level.intValue() == Level.INFO.intValue() -> Log.INFO
    else -> Log.DEBUG
  }

object AndroidLog {
  private const val MAX_LOG_LENGTH = 4000

  @SuppressSignatureCheck
  internal fun androidLog(tag: String, logLevel: Int, message: String, t: Throwable?) {
    var logMessage = message
    if (t != null) logMessage = logMessage + '\n'.toString() + Log.getStackTraceString(t)

    // Split by line, then ensure each line can fit into Log's maximum length.
    var i = 0
    val length = logMessage.length
    while (i < length) {
      var newline = logMessage.indexOf('\n', i)
      newline = if (newline != -1) newline else length
      do {
        val end = minOf(newline, i + MAX_LOG_LENGTH)
        Log.println(logLevel, tag, logMessage.substring(i, end))
        i = end
      } while (i < newline)
      i++
    }
  }

  // Keep references to loggers to prevent their configuration from being GC'd.
  private val configuredLoggers = CopyOnWriteArraySet<Logger>()

  fun enable() {
    enable(OkHttpClient::class.java.`package`.name, "OkHttp")
    enable(OkHttpClient::class)
    enable(Http2::class)
    enable(TaskRunner::class)
  }

  fun enable(loggerClass: KClass<*>) {
    enable(loggerClass.java.name, loggerClass.simpleName!!)
  }

  private fun enable(fullName: String, logName: String) {
    val logger = Logger.getLogger(fullName)
    if (configuredLoggers.add(logger)) {
      logger.useParentHandlers = false
      logger.level = when {
        Log.isLoggable(logName, Log.DEBUG) -> Level.FINE
        Log.isLoggable(logName, Log.INFO) -> Level.INFO
        else -> Level.WARNING
      }
      logger.addHandler(object : Handler() {
        override fun publish(record: LogRecord) {
          androidLog(logName, record.androidLevel, record.message, record.thrown)
        }

        override fun flush() {
        }

        override fun close() {
        }
      })
    }
  }
}
