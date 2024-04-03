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

import android.annotation.SuppressLint
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Collections
import okio.FileSystem
import okio.Path
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

internal object CacheLock {
  private val openCaches = Collections.synchronizedMap(mutableMapOf<Path, Exception>())

  fun openLock(
    fileSystem: FileSystem,
    directory: Path,
  ): Closeable {
    val useFileSystemLock = true // !Platform.isAndroid
    return if (useFileSystemLock) {
      fileSystemLock(inMemoryLock(directory), directory)
    } else {
      inMemoryLock(directory)
    }
  }

  /**
   * Create an in-memory lock, avoiding two open Cache instances.
   */
  @SuppressLint("NewApi")
  @IgnoreJRERequirement // D8 supports put if absent
  fun inMemoryLock(directory: Path): Closeable {
    val existing = openCaches.putIfAbsent(directory, Exception("CacheLock($directory)"))
    if (existing != null) {
      throw IllegalStateException("Cache already open at '$directory' in same process", existing)
    }
    return okio.Closeable {
      openCaches.remove(directory)
    }
  }

  /**
   * Create a file system lock, that excludes other processes. However within the process a
   * memory lock is also needed, since locks don't work within a single process.
   */
  @SuppressLint("NewApi")
  @IgnoreJRERequirement // only called on JVM
  fun fileSystemLock(
    memoryLock: Closeable,
    directory: Path,
  ): Closeable {
    val lockFile = directory / "lock"
    lockFile.toFile().createNewFile()
    val channel = FileChannel.open(lockFile.toNioPath(), StandardOpenOption.APPEND)

    checkNotNull(channel.tryLock()) {
      "Cache already open at '$directory' in another process"
    }

    return okio.Closeable {
      memoryLock.close()
      channel.close()
    }
  }
}
