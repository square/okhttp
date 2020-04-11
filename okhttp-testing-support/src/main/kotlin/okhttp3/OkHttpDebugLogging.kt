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
package okhttp3

import java.util.concurrent.CopyOnWriteArraySet
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlin.reflect.KClass
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.http2.Http2

object OkHttpDebugLogging {
  // Keep references to loggers to prevent their configuration from being GC'd.
  private val configuredLoggers = CopyOnWriteArraySet<Logger>()

  fun enableHttp2() = enable(Http2::class)

  fun enableTaskRunner() = enable(TaskRunner::class)

  fun enable(loggerClass: String) {
    val logger = Logger.getLogger(loggerClass)
    if (configuredLoggers.add(logger)) {
      logger.addHandler(ConsoleHandler().apply {
        level = Level.FINE
        formatter = object : SimpleFormatter() {
          override fun format(record: LogRecord) =
            String.format("[%1\$tF %1\$tT] %2\$s %n", record.millis, record.message)
        }
      })
      logger.level = Level.FINEST
    }
  }

  fun enable(loggerClass: KClass<*>) = enable(loggerClass.java.name)
}
