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

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A log handler that records which log messages were published so that a calling test can make
 * assertions about them.
 */
class TestLogHandler(
  private val logger: Logger,
) : TestRule, BeforeEachCallback, AfterEachCallback {
  constructor(loggerName: Class<*>) : this(Logger.getLogger(loggerName.getName()))

  private val logs = LinkedBlockingQueue<String>()

  private val handler =
    object : Handler() {
      override fun publish(logRecord: LogRecord) {
        logs += "${logRecord.level}: ${logRecord.message}"
      }

      override fun flush() {
      }

      override fun close() {
      }
    }

  private var previousLevel: Level? = null

  override fun beforeEach(context: ExtensionContext?) {
    previousLevel = logger.level
    logger.addHandler(handler)
    logger.setLevel(Level.FINEST)
  }

  override fun afterEach(context: ExtensionContext?) {
    logger.setLevel(previousLevel)
    logger.removeHandler(handler)
  }

  override fun apply(
    base: Statement,
    description: Description,
  ): Statement {
    return object : Statement() {
      override fun evaluate() {
        beforeEach(null)
        try {
          base.evaluate()
        } finally {
          afterEach(null)
        }
      }
    }
  }

  fun takeAll(): List<String> {
    val list = mutableListOf<String>()
    logs.drainTo(list)
    return list
  }

  fun take(): String {
    return logs.poll(10, TimeUnit.SECONDS)
      ?: throw AssertionError("Timed out waiting for log message.")
  }
}
