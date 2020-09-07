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
import java.util.Collections
import okhttp3.TestUtil.isDescendentOf
import okio.ForwardingSink
import okio.ForwardingSource
import okio.IOException
import okio.Sink
import okio.Source

/**
 * Emulate Windows file system limitations on any file system. In particular, Windows will throw an
 * [IOException] when asked to delete or rename an open file.
 */
class WindowsFileSystem(val delegate: FileSystem) : FileSystem {
  /** Guarded by itself. */
  private val openFiles = Collections.synchronizedList(mutableListOf<File>())

  override fun source(file: File): Source = FileSource(file, delegate.source(file))

  override fun sink(file: File): Sink = FileSink(file, delegate.sink(file))

  override fun appendingSink(file: File): Sink = FileSink(file, delegate.appendingSink(file))

  override fun delete(file: File) {
    val fileOpen = file in openFiles
    if (fileOpen) throw IOException("file is open $file")
    delegate.delete(file)
  }

  override fun exists(file: File) = delegate.exists(file)

  override fun size(file: File) = delegate.size(file)

  override fun rename(from: File, to: File) {
    val fromOpen = from in openFiles
    if (fromOpen) throw IOException("file is open $from")

    val toOpen = to in openFiles
    if (toOpen) throw IOException("file is open $to")

    delegate.rename(from, to)
  }

  override fun deleteContents(directory: File) {
    val openChild = synchronized(openFiles) {
      openFiles.firstOrNull { it.isDescendentOf(directory) }
    }
    if (openChild != null) throw IOException("file is open $openChild")
    delegate.deleteContents(directory)
  }

  private inner class FileSink(val file: File, delegate: Sink) : ForwardingSink(delegate) {
    var closed = false

    init {
      openFiles += file
    }

    override fun close() {
      if (!closed) {
        closed = true
        val removed = openFiles.remove(file)
        check(removed)
      }
      delegate.close()
    }
  }

  private inner class FileSource(val file: File, delegate: Source) : ForwardingSource(delegate) {
    var closed = false

    init {
      openFiles += file
    }

    override fun close() {
      if (!closed) {
        closed = true
        val removed = openFiles.remove(file)
        check(removed)
      }
      delegate.close()
    }
  }

  override fun toString() = "$delegate for Windows™"
}
