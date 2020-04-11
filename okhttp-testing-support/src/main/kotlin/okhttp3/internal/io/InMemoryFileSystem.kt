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
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** A simple file system where all files are held in memory. Not safe for concurrent use.  */
class InMemoryFileSystem : FileSystem, TestRule {
  private val files: MutableMap<File, Buffer> = mutableMapOf()
  private val openSources: MutableMap<Source, File> = IdentityHashMap()
  private val openSinks: MutableMap<Sink, File> = IdentityHashMap()

  override fun apply(
    base: Statement,
    description: Description
  ): Statement {
    return object : Statement() {
      @Throws(Throwable::class) override fun evaluate() {
        base.evaluate()
        ensureResourcesClosed()
      }
    }
  }

  fun ensureResourcesClosed() {
    val openResources: MutableList<String> = mutableListOf()
    for (file in openSources.values) {
      openResources.add("Source for $file")
    }
    for (file in openSinks.values) {
      openResources.add("Sink for $file")
    }
    if (!openResources.isEmpty()) {
      val builder =
        StringBuilder("Resources acquired but not closed:")
      for (resource in openResources) {
        builder.append("\n * ")
            .append(resource)
      }
      throw IllegalStateException(builder.toString())
    }
  }

  @Throws(
      FileNotFoundException::class
  ) override fun source(file: File): Source {
    val result = files[file] ?: throw FileNotFoundException()
    val source: Source = result.clone()
    openSources[source] = file
    return object : ForwardingSource(source) {
      @Throws(IOException::class) override fun close() {
        openSources.remove(source)
        super.close()
      }
    }
  }

  @Throws(FileNotFoundException::class)
  override fun sink(file: File): Sink {
    return sink(file, false)
  }

  @Throws(
      FileNotFoundException::class
  ) override fun appendingSink(file: File): Sink {
    return sink(file, true)
  }

  private fun sink(
    file: File,
    appending: Boolean
  ): Sink {
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
      @Throws(IOException::class) override fun close() {
        openSinks.remove(sink)
        super.close()
      }
    }
  }

  @Throws(IOException::class)
  override fun delete(file: File) {
    files.remove(file)
  }

  override fun exists(file: File): Boolean {
    return files.containsKey(file)
  }

  override fun size(file: File): Long {
    val buffer = files[file]
    return buffer?.size ?: 0L
  }

  @Throws(IOException::class) override fun rename(
    from: File,
    to: File
  ) {
    val buffer = files.remove(from) ?: throw FileNotFoundException()
    files[to] = buffer
  }

  @Throws(
      IOException::class
  ) override fun deleteContents(directory: File) {
    val prefix = "$directory/"
    val i = files.keys.iterator()
    while (i.hasNext()) {
      val file = i.next()
      if (file.toString()
              .startsWith(prefix)
      ) i.remove()
    }
  }

  override fun toString() = "InMemoryFileSystem"
}
