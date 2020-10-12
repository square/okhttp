/*
 * Copyright (C) 2011 Google Inc.
 * Copyright (C) 2013 Square, Inc.
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

package okhttp3.mockwebserver

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A scriptable web server. Callers supply canned responses and the server replays them upon request
 * in sequence.
 */
class MockWebServer : TestRule, SimpleMockWebServer() {
  override fun apply(base: Statement, description: Description?): Statement {
    return statement(base)
  }

  private fun statement(base: Statement): Statement {
    return object : Statement() {
      @Throws(Throwable::class)
      override fun evaluate() {
        // Server may have been started manually or implicitly by accessing properties
        if (!started) {
          start()
        }

        val errors = ArrayList<Throwable>()
        try {
          base.evaluate()
        } catch (t: Throwable) {
          errors.add(t)
        } finally {
          try {
            shutdown()
          } catch (e: IOException) {
            logger.log(Level.WARNING, "MockWebServer shutdown failed", e)
          } catch (t: Throwable) {
            errors.add(t)
          }
        }
        MultipleFailureException.assertEmpty(errors)
      }
    }
  }

  companion object {
    private val logger = Logger.getLogger(MockWebServer::class.java.name)
  }
}
