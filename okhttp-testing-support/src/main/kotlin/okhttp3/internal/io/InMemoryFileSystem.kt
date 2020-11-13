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

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.IdentityHashMap
import okio.Buffer
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Sink
import okio.Source
import okhttp3.TestUtil.isDescendentOf
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** A simple file system where all files are held in memory. Not safe for concurrent use.  */
class InMemoryFileSystem : FileSystem, AfterEachCallback {
  val files = mutableMapOf<File, Buffer>()
  private val openSources = IdentityHashMap<Source, File>()
  private val openSinks = IdentityHashMap<Sink, File>()

  override fun afterEach(context: ExtensionContext?) {
    ensureResourcesClosed()
  }

  fun ensureResourcesClosed() {
    val openResources = mutableListOf<String>()
    for (file in openSources.values) {
      openResources.add("Source for $file")
    }
    for (file in openSinks.values) {
      openResources.add("Sink for $file")
    }
    check(openResources.isEmpty()) {
      "Resources acquired but not closed:\n * ${openResources.joinToString(separator = "\n * ")}"
    }
  }

  @Throws(FileNotFoundException::class)
  override fun source(file: File): Source {
    val result = files[file] ?: throw FileNotFoundException()
    val source: Source = result.clone()
    openSources[source] = file
    return object : ForwardingSource(source) {
      override fun close() {
        openSources.remove(source)
        super.close()
      }
    }
  }

  @Throws(FileNotFoundException::class)
  override fun sink(file: File) = sink(file, false)

  @Throws(FileNotFoundException::class)
  override fun appendingSink(file: File) = sink(file, true)

  private fun sink(file: File, appending: Boolean): Sink {
    var result: Buffer? = null
    if (appending) {
      result = files[file]
    }
    if (result == null) {
      result = Buffer()
    }
    files[file] = result
    val sink: Sink = result
    openSinks[sink] = file
    return object : ForwardingSink(sink) {
      override fun close() {
        openSinks.remove(sink)
        super.close()
      }
    }
  }

  @Throws(IOException::class)
  override fun delete(file: File) {
    files.remove(file)
  }

  override fun exists(file: File) = files.containsKey(file)

  override fun size(file: File) = files[file]?.size ?: 0L

  @Throws(IOException::class)
  override fun rename(from: File, to: File) {
    files[to] = files.remove(from) ?: throw FileNotFoundException()
  }

  @Throws(IOException::class)
  override fun deleteContents(directory: File) {
    val i = files.keys.iterator()
    while (i.hasNext()) {
      val file = i.next()
      if (file.isDescendentOf(directory)) i.remove()
    }
  }

  override fun toString() = "InMemoryFileSystem"
}
