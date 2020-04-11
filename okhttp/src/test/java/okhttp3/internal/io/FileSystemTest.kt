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

import java.io.File
import java.io.IOException
import okhttp3.TestUtil
import okio.buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/**
 * Test that our file system abstraction is consistent and sufficient for OkHttp's needs. We're
 * particularly interested in what happens when open files are moved or deleted on Windows.
 */
@RunWith(Parameterized::class)
class FileSystemTest(
  private var fileSystem: FileSystem,
  private val windows: Boolean
) {
  @Rule @JvmField val temporaryFolder = TemporaryFolder()

  companion object {
    @Parameters(name = "{0}") @JvmStatic
    fun parameters(): Collection<Array<Any>> = listOf(
        arrayOf(FileSystem.SYSTEM, TestUtil.windows),
        arrayOf(InMemoryFileSystem(), false),
        arrayOf(WindowsFileSystem(FileSystem.SYSTEM), true),
        arrayOf(WindowsFileSystem(InMemoryFileSystem()), true)
    )
  }

  @Test fun `delete open for writing fails on Windows`() {
    val file = temporaryFolder.newFile("file.txt")
    expectIOExceptionOnWindows {
      fileSystem.sink(file).use {
        fileSystem.delete(file)
      }
    }
  }

  @Test fun `delete open for appending fails on Windows`() {
    val file = temporaryFolder.newFile("file.txt")
    file.write("abc")
    expectIOExceptionOnWindows {
      fileSystem.appendingSink(file).use {
        fileSystem.delete(file)
      }
    }
  }

  @Test fun `delete open for reading fails on Windows`() {
    val file = temporaryFolder.newFile("file.txt")
    file.write("abc")
    expectIOExceptionOnWindows {
      fileSystem.source(file).use {
        fileSystem.delete(file)
      }
    }
  }

  @Test fun `rename target exists succeeds on all platforms`() {
    val from = temporaryFolder.newFile("from.txt")
    val to = temporaryFolder.newFile("to.txt")
    from.write("source file")
    to.write("target file")
    fileSystem.rename(from, to)
  }

  @Test fun `rename source is open fails on Windows`() {
    val from = temporaryFolder.newFile("from.txt")
    val to = temporaryFolder.newFile("to.txt")
    from.write("source file")
    to.write("target file")
    expectIOExceptionOnWindows {
      fileSystem.source(from).use {
        fileSystem.rename(from, to)
      }
    }
  }

  @Test fun `rename target is open fails on Windows`() {
    val from = temporaryFolder.newFile("from.txt")
    val to = temporaryFolder.newFile("to.txt")
    from.write("source file")
    to.write("target file")
    expectIOExceptionOnWindows {
      fileSystem.source(to).use {
        fileSystem.rename(from, to)
      }
    }
  }

  @Test fun `delete contents of parent of file open for reading fails on Windows`() {
    val parentA = temporaryFolder.newFolder("a")
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
