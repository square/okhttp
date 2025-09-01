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
package okhttp3.internal.cache

import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Collections
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * An implementation of directory locking to ensure exclusive access in a Cache instance.
 * Always applies for the current process, and uses a file system lock if supported.
 */
internal object CacheLock {
  private val openCaches = Collections.synchronizedMap(mutableMapOf<Path, Exception>())

  /**
   * Open a lock, which if successful remains until the returned [Closeable] is closed.
   * The lock file will be a file with name "lock" inside directory.
   *
   * @param fileSystem the file system containing the lock files.
   * @param directory the cache directory.
   */
  fun openLock(
    fileSystem: FileSystem,
    directory: Path,
  ): Closeable {
    val memoryLock = inMemoryLock(directory)

    // check if possibly a non System file system
    if (FileSystem.SYSTEM.exists(directory)) {
      try {
        val fileSystemLock = fileSystemLock(directory)

        return Closeable {
          memoryLock.close()
          fileSystemLock.close()
        }
      } catch (le: LockException) {
        if (fileSystemSupportsLock(fileSystem)) {
          memoryLock.close()
          throw le
        }
      }
    }

    return memoryLock
  }

  @IgnoreJRERequirement // Not called on legacy Android
  internal fun fileSystemSupportsLock(fileSystem: FileSystem): Boolean {
    val tmpLockFile = File.createTempFile("test-", ".lock")

    if (!fileSystem.exists(tmpLockFile.toOkioPath())) {
      return false
    }

    val channel = FileChannel.open(tmpLockFile.toPath(), StandardOpenOption.APPEND)

    return channel.tryLock().apply { close() } != null
  }

  /**
   * Create an in-memory lock, avoiding two open Cache instances.
   */
  @IgnoreJRERequirement // D8 supports put if absent
  internal fun inMemoryLock(directory: Path): Closeable {
    val existing = openCaches.putIfAbsent(directory, LockException("Existing CacheLock($directory) opened at"))
    if (existing != null) {
      throw LockException("Cache already open at '$directory' in same process", existing)
    }
    return okio.Closeable {
      openCaches.remove(directory)
    }
  }

  /**
   * Create a file system lock, that excludes other processes. However within the process a
   * memory lock is also needed, since locks don't work within a single process.
   */
  @IgnoreJRERequirement // Not called on legacy Android
  internal fun fileSystemLock(directory: Path): Closeable {
    // update once https://github.com/square/okio/issues/1464 is available

    val lockFile = directory / "lock"
    lockFile.toFile().createNewFile()
    val channel = FileChannel.open(lockFile.toNioPath(), StandardOpenOption.APPEND)

    channel.tryLock() ?: throw LockException("Cache already open at '$directory' in another process")

    return okio.Closeable {
      channel.close()
    }
  }
}

class LockException(
  message: String,
  cause: Exception? = null,
) : Exception(message, cause)
