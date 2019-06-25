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

import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.lang.reflect.InvocationTargetException

@RunWith(Parameterized::class)
@Ignore
class AllMainsTest(val className: String) {
  @Test
  fun runMain() {
    val mainMethod = Class.forName(className).methods.find { it.name == "main" }
    try {
      mainMethod?.invoke(null, arrayOf<String>())
    } catch (ite: InvocationTargetException) {
      if (!expectedFailure(className, ite.cause!!)) {
        throw ite.cause!!
      }
    }
  }

  private fun expectedFailure(className: String, cause: Throwable): Boolean {
    return when (className) {
      "okhttp3.recipes.CheckHandshake" -> true // by design
      "okhttp3.recipes.RequestBodyCompression" -> true // expired token
      else -> false
    }
  }

  companion object {
    private val prefix = if (File("samples").exists()) "" else "../../"

    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun data(): List<String> {
      val mainFiles = mainFiles()
      return mainFiles.map {
        it.path.substring("$prefix/samples/guide/src/main/java".length, it.path.length - 5)
            .replace('/', '.')
      }.sorted()
    }

    private fun mainFiles(): List<File> {
      return File("$prefix/samples/guide/src/main/java/okhttp3").listFiles()?.flatMap {
        it?.listFiles()?.toList().orEmpty()
      }.orEmpty()
    }
  }
}
