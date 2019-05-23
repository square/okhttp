/*
 * Copyright (C) 2015 Square, Inc.
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

import okio.Source
import okio.source
import okio.Sink
import okio.sink
import okio.appendingSink
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Access to read and write files on a hierarchical data store. Most callers should use the
 * [SYSTEM] implementation, which uses the host machine's local file system. Alternate
 * implementations may be used to inject faults (for testing) or to transform stored data (to add
 * encryption, for example).
 *
 * All operations on a file system are racy. For example, guarding a call to [source] with
 * [exists] does not guarantee that [FileNotFoundException] will not be thrown. The
 * file may be moved between the two calls!
 *
 * This interface is less ambitious than [java.nio.file.FileSystem] introduced in Java 7.
 * It lacks important features like file watching, metadata, permissions, and disk space
 * information. In exchange for these limitations, this interface is easier to implement and works
 * on all versions of Java and Android.
 */
interface FileSystem {

  companion object {
    /** The host machine's local file system. */
    @JvmField
    val SYSTEM: FileSystem = object : FileSystem {
      @Throws(FileNotFoundException::class)
      override fun source(file: File): Source = file.source()

      @Throws(FileNotFoundException::class)
      override fun sink(file: File): Sink {
        return try {
          file.sink()
        } catch (_: FileNotFoundException) {
          // Maybe the parent directory doesn't exist? Try creating it first.
          file.parentFile.mkdirs()
          file.sink()
        }
      }

      @Throws(FileNotFoundException::class)
      override fun appendingSink(file: File): Sink {
          return try {
              file.appendingSink()
          } catch (_: FileNotFoundException) {
              // Maybe the parent directory doesn't exist? Try creating it first.
              file.parentFile.mkdirs()
              file.appendingSink()
          }
      }

      @Throws(IOException::class)
      override fun delete(file: File) {
        // If delete() fails, make sure it's because the file didn't exist!
        if (!file.delete() && file.exists()) {
          throw IOException("failed to delete $file")
        }
      }

      override fun exists(file: File): Boolean = file.exists()

      override fun size(file: File): Long = file.length()

      @Throws(IOException::class)
      override fun rename(from: File, to: File) {
        delete(to)
        if (!from.renameTo(to)) {
          throw IOException("failed to rename $from to $to")
        }
      }

      @Throws(IOException::class)
      override fun deleteContents(directory: File) {
        val files = directory.listFiles() ?: throw IOException("not a readable directory: $directory")
        for (file in files) {
          if (file.isDirectory) {
            deleteContents(file)
          }
          if (!file.delete()) {
            throw IOException("failed to delete $file")
          }
        }
      }
    }
  }

  /** Reads from [file]. */
  @Throws(FileNotFoundException::class)
  fun source(file: File): Source

  /**
   * Writes to [file], discarding any data already present. Creates parent directories if
   * necessary.
   */
  @Throws(FileNotFoundException::class)
  fun sink(file: File): Sink

  /**
   * Writes to [file], appending if data is already present. Creates parent directories if
   * necessary.
   */
  @Throws(FileNotFoundException::class)
  fun appendingSink(file: File): Sink

  /** Deletes [file] if it exists. Throws if the file exists and cannot be deleted. */
  @Throws(IOException::class)
  fun delete(file: File)

  /** Returns true if [file] exists on the file system. */
  fun exists(file: File): Boolean

  /** Returns the number of bytes stored in [file], or 0 if it does not exist. */
  fun size(file: File): Long

  /** Renames [from] to [to]. Throws if the file cannot be renamed. */
  @Throws(IOException::class)
  fun rename(from: File, to: File)

  /**
   * Recursively delete the contents of [directory]. Throws an IOException if any file could
   * not be deleted, or if `dir` is not a readable directory.
   */
  @Throws(IOException::class)
  fun deleteContents(directory: File)
}
