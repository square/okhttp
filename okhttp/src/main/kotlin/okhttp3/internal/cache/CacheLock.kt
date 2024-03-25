package okhttp3.internal.cache

import android.annotation.SuppressLint
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Collections
import okhttp3.internal.platform.Platform
import okio.FileSystem
import okio.Path

internal object CacheLock {
  private val openCaches = Collections.synchronizedMap(mutableMapOf<Path, Exception>())

  fun openLock(fileSystem: FileSystem, directory: Path): Closeable {
    return if (fileSystem == FileSystem.SYSTEM && !Platform.isAndroid) {
      fileSystemLock(directory)
    } else {
      inMemoryLock(directory)
    }
  }

  @SuppressLint("NewApi")
  fun inMemoryLock(directory: Path): Closeable {
    // TODO solution for Android N?
    val existing = openCaches.putIfAbsent(directory, Exception("CacheLock($directory)"))
    if (existing != null) {
      throw IllegalStateException("Cache already open at '$directory'", existing)
    }
    return okio.Closeable {
      openCaches.remove(directory)
    }
  }

  @SuppressLint("NewApi")
  fun fileSystemLock(directory: Path): Closeable {
    val lockFile = directory / "lock"
    val channel = FileChannel.open(lockFile.toNioPath(), StandardOpenOption.APPEND)

    return okio.Closeable {
      channel.close()
    }
  }
}
