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

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource
import java.io.File
import java.lang.reflect.InvocationTargetException

private val prefix = if (File("samples").exists()) "" else "../../"

private fun mainFiles(): List<File> {
  val directories = listOf(
    "$prefix/samples/guide/src/main/java/okhttp3/guide",
    "$prefix/samples/guide/src/main/java/okhttp3/recipes",
    "$prefix/samples/guide/src/main/java/okhttp3/recipes/kt"
  ).map { File(it) }

  return directories.flatMap {
    it.listFiles().orEmpty().filter { f -> f.isFile }.toList()
  }
}

internal class MainTestProvider : SimpleProvider() {
  override fun arguments(): List<Any> {
    val mainFiles = mainFiles()
    return mainFiles.map {
      val suffix = it.path.replace("${prefix}samples/guide/src/main/java/", "")
      suffix.replace("(.*)\\.java".toRegex()) { mr ->
        mr.groupValues[1].replace('/', '.')
      }.replace("(.*)\\.kt".toRegex()) { mr ->
        mr.groupValues[1].replace('/', '.') + "Kt"
      }
    }.sorted()
  }
}

@Disabled("Don't run by default")
@Tag("Slow")
class AllMainsTest {
  @ParameterizedTest
  @ArgumentsSource(MainTestProvider::class)
  fun runMain(className: String) {
    val mainMethod = Class.forName(className)
        .methods.find { it.name == "main" }
    try {
      if (mainMethod != null) {
        if (mainMethod.parameters.isEmpty()) {
          mainMethod.invoke(null)
        } else {
          mainMethod.invoke(null, arrayOf<String>())
        }
      } else {
        System.err.println("No main for $className")
      }
    } catch (ite: InvocationTargetException) {
      if (!expectedFailure(className, ite.cause!!)) {
        throw ite.cause!!
      }
    }
  }

  @Suppress("UNUSED_PARAMETER")
  private fun expectedFailure(
    className: String,
    cause: Throwable
  ): Boolean {
    return when (className) {
      "okhttp3.recipes.CheckHandshake" -> true // by design
      "okhttp3.recipes.RequestBodyCompression" -> true // expired token
      else -> false
    }
  }
}
