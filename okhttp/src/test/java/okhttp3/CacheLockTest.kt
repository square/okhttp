/*
 * Copyright (C) 2024 Block, Inc.
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

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.nio.file.Path
import okio.Closeable
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class CacheLockTest {
  private lateinit var tempDir: okio.Path
  private val toClose = mutableListOf<Closeable>()

  @BeforeEach
  fun setup(
    @TempDir tempDir: Path,
  ) {
    this.tempDir = tempDir.toOkioPath()
  }

  @AfterEach
  fun cleanup() {
    toClose.forEach {
      it.close()
    }
  }

  @Test
  fun testCacheLock() {
    openCache(tempDir)

    val ioe =
      assertThrows<IllegalStateException> {
        openCache(tempDir)
      }
    assertThat(ioe.message).isEqualTo("Cache already open at '$tempDir' in same process")
  }

  @Test
  fun testCacheLockAfterClose() {
    val cache1 = openCache(tempDir)

    cache1.close()

    openCache(tempDir)
  }

  @Test
  fun testCacheLockDifferentPath() {
    openCache(tempDir / "a")

    openCache(tempDir / "b")
  }

  private fun openCache(directory: okio.Path): Cache {
    return Cache(directory, 10_000, FileSystem.SYSTEM).apply {
      // force early LRU initialisation
      initialize()
      toClose.add(this)
    }
  }
}
