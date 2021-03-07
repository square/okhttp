/*
 * Copyright (C) 2011 The Android Open Source Project
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

import okio.Buffer
import okio.ExperimentalFileSystem
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.Path
import okio.Sink
import java.io.IOException
import java.util.LinkedHashSet

@OptIn(ExperimentalFileSystem::class)
class FaultyFileSystem constructor(delegate: FileSystem?) : ForwardingFileSystem(delegate!!) {
  private val writeFaults: MutableSet<Path> = LinkedHashSet()
  private val deleteFaults: MutableSet<Path> = LinkedHashSet()
  private val renameFaults: MutableSet<Path> = LinkedHashSet()

  fun setFaultyWrite(file: Path, faulty: Boolean) {
    if (faulty) {
      writeFaults.add(file)
    } else {
      writeFaults.remove(file)
    }
  }

  fun setFaultyDelete(file: Path, faulty: Boolean) {
    if (faulty) {
      deleteFaults.add(file)
    } else {
      deleteFaults.remove(file)
    }
  }

  fun setFaultyRename(file: Path, faulty: Boolean) {
    if (faulty) {
      renameFaults.add(file)
    } else {
      renameFaults.remove(file)
    }
  }

  @Throws(IOException::class)
  override fun atomicMove(source: Path, target: Path) {
    if (renameFaults.contains(source) || renameFaults.contains(target)) throw IOException("boom!")
    super.atomicMove(source, target)
  }

  @Throws(IOException::class)
  override fun delete(path: Path) {
    if (deleteFaults.contains(path)) throw IOException("boom!")
    super.delete(path)
  }

  @Throws(IOException::class)
  override fun deleteRecursively(fileOrDirectory: Path) {
    if (deleteFaults.contains(fileOrDirectory)) throw IOException("boom!")
    super.deleteRecursively(fileOrDirectory)
  }

  override fun appendingSink(file: Path): Sink = FaultySink(super.appendingSink(file), file)

  override fun sink(file: Path): Sink = FaultySink(super.sink(file), file)

  inner class FaultySink(sink: Sink, private val file: Path) : ForwardingSink(sink) {
    override fun write(source: Buffer, byteCount: Long) {
      if (writeFaults.contains(file)) {
        throw IOException("boom!")
      } else {
        super.write(source, byteCount)
      }
    }
  }
}