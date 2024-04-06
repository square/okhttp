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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.internal.cache.LockException
import okhttp3.testing.PlatformRule
import okhttp3.testing.PlatformVersion
import okio.Closeable
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir

@org.junit.jupiter.api.parallel.Isolated
class CacheLockTest {
  @JvmField
  @RegisterExtension
  val platform = PlatformRule()

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

    val lockException =
      assertThrows<LockException> {
        openCache(tempDir)
      }
    assertThat(lockException.message).isEqualTo("Cache already open at '$tempDir' in same process")
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

  @Test
  fun testCacheLockDifferentProcess() {
    runBlocking {
      // No java command to execute LockTestProgram.java
      platform.assumeNotAndroid()
      assumeTrue(PlatformVersion.majorVersion >= 11)

      val lockFile = tempDir / "lock"
      lockFile.toFile().createNewFile()

      val javaExe =
        if (PlatformVersion.majorVersion >= 9) {
          @Suppress("Since15")
          ProcessHandle.current().info().command().get().toPath()
        } else {
          System.getenv("JAVA_HOME").toPath() / "bin/java"
        }

      val process =
        ProcessBuilder().command(
          javaExe.toString(),
          "src/test/java/okhttp3/LockTestProgram.java",
          (lockFile.toString()),
        )
          .redirectErrorStream(true)
          .start()

      val output = process.inputStream.bufferedReader()

      try {
        withTimeout(5.seconds) {
          assertThat(output.readLine()).isEqualTo("Locking $lockFile")
          assertThat(output.readLine()).isEqualTo("Locked $lockFile")
        }

        val lockException =
          assertThrows<LockException> {
            openCache(tempDir)
          }
        assertThat(lockException.message).isEqualTo("Cache already open at '$tempDir' in another process")
      } finally {
        process.destroy()
      }

      delay(100)

      // Should work again once process is killed
      openCache(tempDir)
    }
  }

  private fun openCache(directory: okio.Path): Cache {
    return Cache(directory, 10_000, FileSystem.SYSTEM).apply {
      // force early LRU initialisation
      initialize()
      toClose.add(this)
    }
  }
}
