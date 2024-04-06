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

import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.LogRecord
import java.util.logging.Logger
import okhttp3.internal.http2.Http2

class LoggingUtil {
  companion object {
    private val activeLoggers = mutableListOf<Logger>()

    fun configureLogging(
      debug: Boolean,
      showHttp2Frames: Boolean,
      sslDebug: Boolean,
    ) {
      if (debug || showHttp2Frames || sslDebug) {
        if (sslDebug) {
          System.setProperty("javax.net.debug", "")
        }
        LogManager.getLogManager().reset()
        val handler =
          object : ConsoleHandler() {
            override fun publish(record: LogRecord) {
              super.publish(record)

              val parameters = record.parameters
              if (sslDebug && record.loggerName == "javax.net.ssl" && parameters != null) {
                System.err.println(parameters[0])
              }
            }
          }

        if (debug) {
          handler.level = Level.ALL
          handler.formatter = OneLineLogFormat()
          val activeLogger = getLogger("")
          activeLogger.addHandler(handler)
          activeLogger.level = Level.ALL

          getLogger("jdk.event.security").level = Level.INFO
          getLogger("org.conscrypt").level = Level.INFO
        } else {
          if (showHttp2Frames) {
            val activeLogger = getLogger(Http2::class.java.name)
            activeLogger.level = Level.FINE
            handler.level = Level.FINE
            handler.formatter = MessageFormatter
            activeLogger.addHandler(handler)
          }

          if (sslDebug) {
            val activeLogger = getLogger("javax.net.ssl")

            activeLogger.level = Level.FINEST
            handler.level = Level.FINEST
            handler.formatter = MessageFormatter
            activeLogger.addHandler(handler)
          }
        }
      }
    }

    fun getLogger(name: String): Logger {
      val logger = Logger.getLogger(name)
      activeLoggers.add(logger)
      return logger
    }
  }
}
