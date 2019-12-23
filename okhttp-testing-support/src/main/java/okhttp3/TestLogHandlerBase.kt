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

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * A log handler that records which log messages were published so that a calling test can make
 * assertions about them.
 */
class TestLogHandlerBase(
  private val logger: Logger,
  private val logConsumer: (String) -> Unit
) : Handler(), TestRule {
  override fun apply(
    base: Statement,
    description: Description
  ): Statement {
    return object : Statement() {
      @Throws(Throwable::class) override fun evaluate() {
        val previousLevel = install()
        try {
          base.evaluate()
        } finally {
          uninstall(previousLevel)
        }
      }
    }
  }

  fun install(): Level? {
    val previousLevel = logger.level
    logger.addHandler(this@TestLogHandlerBase)
    logger.level = Level.FINEST
    return previousLevel
  }

  fun uninstall(previousLevel: Level?) {
    logger.level = previousLevel
    logger.removeHandler(this@TestLogHandlerBase)
  }

  override fun publish(logRecord: LogRecord) {
    val logString = logRecord.level.toString() + ": " + logRecord.message
    logConsumer.invoke(logString)
  }

  override fun flush() {}
  override fun close() {}
}