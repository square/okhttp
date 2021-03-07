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
package mockwebserver3.junit5.internal

import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger
import mockwebserver3.MockWebServer
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

/** Runs MockWebServer for the duration of a single test method. */
class MockWebServerExtension
  : BeforeAllCallback, BeforeEachCallback, AfterAllCallback, AfterEachCallback, ParameterResolver {
  private val ExtensionContext.resource: Resource
    get() {
      val store = getStore(namespace)
      var result = store.get(uniqueId) as Resource?
      if (result == null) {
        result = Resource()
        store.put(uniqueId, result)
      }
      return result
    }

  private class Resource {
    private val servers = mutableListOf<MockWebServer>()
    private var started = false

    fun newServer(): MockWebServer {
      return MockWebServer()
        .also { result ->
          if (started) result.start()
          servers += result
        }
    }

    fun startAll() {
      started = true
      for (server in servers) {
        server.start()
      }
    }

    fun shutdownAll() {
      try {
        for (server in servers) {
          server.shutdown()
        }
      } catch (e: IOException) {
        logger.log(Level.WARNING, "MockWebServer shutdown failed", e)
      }
    }
  }

  @IgnoreJRERequirement
  override fun supportsParameter(
    parameterContext: ParameterContext,
    extensionContext: ExtensionContext
  ): Boolean = parameterContext.parameter.type === MockWebServer::class.java

  override fun resolveParameter(
    parameterContext: ParameterContext,
    extensionContext: ExtensionContext
  ): Any = extensionContext.resource.newServer()

  /** Start the servers passed in as test class constructor parameters. */
  override fun beforeAll(context: ExtensionContext) {
    context.resource.startAll()
  }

  /** Start the servers passed in as test method parameters. */
  override fun beforeEach(context: ExtensionContext) {
    context.resource.startAll()
  }

  override fun afterEach(context: ExtensionContext) {
    context.resource.shutdownAll()
  }

  override fun afterAll(context: ExtensionContext) {
    context.resource.shutdownAll()
  }

  companion object {
    private val logger = Logger.getLogger(MockWebServerExtension::class.java.name)
    private val namespace = ExtensionContext.Namespace.create(MockWebServerExtension::class.java)
  }
}
