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
package okhttp3.internal.io

import okhttp3.SimpleProvider
import okhttp3.TestUtil
import okio.buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource
import java.io.File
import java.io.IOException

class FileSystemParamProvider: SimpleProvider() {
  override fun arguments() = listOf(
    FileSystem.SYSTEM to TestUtil.windows,
    InMemoryFileSystem() to false,
    WindowsFileSystem(FileSystem.SYSTEM) to true,
    WindowsFileSystem(InMemoryFileSystem()) to true
  )
}

/**
 * Test that our file system abstraction is consistent and sufficient for OkHttp's needs. We're
 * particularly interested in what happens when open files are moved or deleted on Windows.
 */
class FileSystemTest {
  @TempDir lateinit var temporaryFolder: File

  private lateinit var fileSystem: FileSystem
  private var windows: Boolean = false

  internal fun setUp(fileSystem: FileSystem, windows: Boolean) {
    this.fileSystem = fileSystem
    this.windows = windows
  }

  @ParameterizedTest
  @ArgumentsSource(FileSystemParamProvider::class)
  fun `delete open for writing fails on Windows`(
    parameters: Pair<FileSystem, Boolean>
  ) {
    setUp(parameters.first, parameters.second)
    val file = File(temporaryFolder, "file.txt")
    expectIOExceptionOnWindows {
      fileSystem.sink(file).use {
        fileSystem.delete(file)
      }
    }
  }

  @ParameterizedTest
  @ArgumentsSource(FileSystemParamProvider::class)
  fun `delete open for appending fails on Windows`(
    parameters: Pair<FileSystem, Boolean>
  ) {
    setUp(parameters.first, parameters.second)
    val file = File(temporaryFolder, "file.txt")
    file.write("abc")
    expectIOExceptionOnWindows {
      fileSystem.appendingSink(file).use {
        fileSystem.delete(file)
      }
    }
  }

  @ParameterizedTest
  @ArgumentsSource(FileSystemParamProvider::class)
  fun `delete open for reading fails on Windows`(
    parameters: Pair<FileSystem, Boolean>
  ) {
    setUp(parameters.first, parameters.second)
    val file = File(temporaryFolder, "file.txt")
    file.write("abc")
    expectIOExceptionOnWindows {
      fileSystem.source(file).use {
        fileSystem.delete(file)
      }
    }
  }

  @ParameterizedTest
  @ArgumentsSource(FileSystemParamProvider::class)
  fun `rename target exists succeeds on all platforms`(
    parameters: Pair<FileSystem, Boolean>
  ) {
    setUp(parameters.first, parameters.second)
    val from = File(temporaryFolder, "from.txt")
    val to = File(temporaryFolder, "to.txt")
    from.write("source file")
    to.write("target file")
    fileSystem.rename(from, to)
  }

  @ParameterizedTest
  @ArgumentsSource(FileSystemParamProvider::class)
  fun `rename source is open fails on Windows`(
    parameters: Pair<FileSystem, Boolean>
  ) {
    setUp(parameters.first, parameters.second)
    val from = File(temporaryFolder, "from.txt")
    val to = File(temporaryFolder, "to.txt")
    from.write("source file")
    to.write("target file")
    expectIOExceptionOnWindows {
      fileSystem.source(from).use {
        fileSystem.rename(from, to)
      }
    }
  }

  @ParameterizedTest
  @ArgumentsSource(FileSystemParamProvider::class)
  fun `rename target is open fails on Windows`(
    parameters: Pair<FileSystem, Boolean>
  ) {
    setUp(parameters.first, parameters.second)
    val from = File(temporaryFolder, "from.txt")
    val to = File(temporaryFolder, "to.txt")
    from.write("source file")
    to.write("target file")
    expectIOExceptionOnWindows {
      fileSystem.source(to).use {
        fileSystem.rename(from, to)
      }
    }
  }

  @ParameterizedTest
  @ArgumentsSource(FileSystemParamProvider::class)
  fun `delete contents of parent of file open for reading fails on Windows`(
    parameters: Pair<FileSystem, Boolean>
  ) {
    setUp(parameters.first, parameters.second)
    val parentA = File(temporaryFolder, "a").also { it.mkdirs() }
    val parentAB = File(parentA, "b")
    val parentABC = File(parentAB, "c")
    val file = File(parentABC, "file.txt")
    file.write("child file")
    expectIOExceptionOnWindows {
      fileSystem.source(file).use {
        fileSystem.deleteContents(parentA)
      }
    }
  }

  private fun File.write(content: String) {
    fileSystem.sink(this).buffer().use {
      it.writeUtf8(content)
    }
  }

  private fun expectIOExceptionOnWindows(block: () -> Unit) {
    try {
      block()
      assertThat(windows).isFalse()
    } catch (_: IOException) {
      assertThat(windows).isTrue()
    }
  }
}
