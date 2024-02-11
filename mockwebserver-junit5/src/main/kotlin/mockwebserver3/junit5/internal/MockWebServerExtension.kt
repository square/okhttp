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
import okhttp3.ExperimentalOkHttpApi
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

/**
 * Runs MockWebServer for the duration of a single test method.
 *
 * Specifically while junit instances passes into test constructor
 * are typically shares amongst all tests, a fresh instance will be
 * received here. Use with @BeforeAll and @AfterAll, is not supported.
 *
 * There are 3 ids for instances
 * - The test instance default (passed into constructor)
 * - The test lifecycle default (passed into test method, plus @BeforeEach, @AfterEach)
 * - named instances with @MockWebServerInstance.
 */
@ExperimentalOkHttpApi
class MockWebServerExtension :
  BeforeEachCallback, AfterEachCallback, ParameterResolver, BeforeAllCallback, AfterAllCallback {
  private val ExtensionContext.resource: ServersForTest
    get() =
      getStore(namespace).getOrComputeIfAbsent(this.uniqueId) {
        ServersForTest()
      } as ServersForTest

  private class ServersForTest {
    private val servers = mutableMapOf<String, MockWebServer>()
    private var started = false

    fun server(name: String): MockWebServer {
      return servers.getOrPut(name) {
        MockWebServer().also {
          if (started) it.start()
        }
      }
    }

    fun startAll() {
      started = true
      for (server in servers.values) {
        server.start()
      }
    }

    fun shutdownAll() {
      try {
        val toClear = servers.values.toList()
        servers.clear()
        for (server in toClear) {
          server.shutdown()
        }
      } catch (e: IOException) {
        logger.log(Level.WARNING, "MockWebServer shutdown failed", e)
      }
    }
  }

  @Suppress("NewApi")
  @IgnoreJRERequirement
  override fun supportsParameter(
    parameterContext: ParameterContext,
    extensionContext: ExtensionContext,
  ): Boolean {
    return parameterContext.parameter.type === MockWebServer::class.java
  }

  @Suppress("NewApi")
  override fun resolveParameter(
    parameterContext: ParameterContext,
    extensionContext: ExtensionContext,
  ): Any {
    val nameAnnotation = parameterContext.findAnnotation(MockWebServerInstance::class.java)
    val name =
      if (nameAnnotation.isPresent) {
        nameAnnotation.get().name
      } else {
        defaultName
      }
    return extensionContext.resource.server(name)
  }

  /** Start the servers passed in as test method parameters. */
  override fun beforeEach(context: ExtensionContext) {
    context.resource.startAll()
  }

  override fun afterEach(context: ExtensionContext) {
    context.resource.shutdownAll()
  }

  override fun beforeAll(context: ExtensionContext) {
    context.resource.startAll()
  }

  override fun afterAll(context: ExtensionContext) {
    context.resource.shutdownAll()
  }

  private companion object {
    private val logger = Logger.getLogger(MockWebServerExtension::class.java.name)
    private val namespace = ExtensionContext.Namespace.create(MockWebServerExtension::class.java)
    private val defaultName = MockWebServerExtension::class.java.simpleName
  }
}
