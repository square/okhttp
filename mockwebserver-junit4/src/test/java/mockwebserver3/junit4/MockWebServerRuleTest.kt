/*
 * Copyright (C) 2020 Square, Inc.
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
package mockwebserver3.junit4

import java.net.ConnectException
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

class MockWebServerRuleTest {
  @Test fun statementStartsAndStops() {
    val rule = MockWebServerRule()
    val called = AtomicBoolean()
    val statement: Statement = rule.apply(object : Statement() {
      @Throws(Throwable::class) override fun evaluate() {
        called.set(true)
        rule.server.url("/").toUrl().openConnection().connect()
      }
    }, Description.EMPTY)
    statement.evaluate()
    assertThat(called.get()).isTrue
    try {
      rule.server.url("/").toUrl().openConnection().connect()
      fail()
    } catch (expected: ConnectException) {
    }
  }
}
