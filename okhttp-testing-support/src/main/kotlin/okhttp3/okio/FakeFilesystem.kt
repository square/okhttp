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
package okhttp3.okio

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okhttp3.okio.FakeFilesystem.Element.Directory
import okhttp3.okio.FakeFilesystem.Element.File
import okio.Buffer
import okio.ByteString
import okio.ExperimentalFilesystem
import okio.FileMetadata
import okio.Filesystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.Timeout
import java.io.FileNotFoundException
import java.io.IOException

/**
 * A fully in-memory filesystem useful for testing. It includes features to support writing
 * better tests.
 *
 * Use [openPaths] to see which paths have been opened for read or write, but not yet closed. Tests
 * should call [checkNoOpenFiles] in `tearDown()` to confirm that no file streams were leaked.
 *
 * By default this filesystem permits deletion and removal of open files. Configure
 * [windowsLimitations] to true to throw an [IOException] when asked to delete or rename an open
 * file.
 */
@ExperimentalFilesystem
class FakeFilesystem(
  private val windowsLimitations: Boolean = false,
  private val workingDirectory: Path = (if (windowsLimitations) "F:\\".toPath() else "/".toPath()),

  @JvmField
  val clock: Clock = Clock.System
) : Filesystem() {

  init {
    require(workingDirectory.isAbsolute) {
      "expected an absolute path but was $workingDirectory"
    }
  }

  /** Keys are canonical paths. Each value is either a [Directory] or a [ByteString]. */
  private val elements = mutableMapOf<Path, Element>()

  /** Files that are currently open and need to be closed to avoid resource leaks. */
  private val openFiles = mutableListOf<OpenFile>()

  /**
   * Canonical paths for every file and directory in this filesystem. This omits filesystem roots
   * like `C:\` and `/`.
   */
  @get:JvmName("allPaths")
  val allPaths: Set<Path>
    get() {
      val result = mutableSetOf<Path>()
      for (path in elements.keys) {
        if (path.isRoot) continue
        result += path
      }
      return result
    }

  /**
   * Canonical paths currently opened for reading or writing in the order they were opened. This may
   * contain duplicates if a single path is open by multiple readers.
   *
   * Note that this may contain paths not present in [allPaths]. This occurs if a file is deleted
   * while it is still open.
   */
  @get:JvmName("openPaths")
  val openPaths: List<Path>
    get() = openFiles.map { it.canonicalPath }

  /**
   * Confirm that all files that have been opened on this filesystem (with [source], [sink], and
   * [appendingSink]) have since been closed. Call this in your test's `tearDown()` function to
   * confirm that your program hasn't leaked any open files.
   *
   * Forgetting to close a file on a real filesystem is a severe error that may lead to a program
   * crash. The operating system enforces a limit on how many files may be open simultaneously. On
   * Linux this is [getrlimit] and is commonly adjusted with the `ulimit` command.
   *
   * [getrlimit]: https://man7.org/linux/man-pages/man2/getrlimit.2.html
   *
   * @throws IllegalStateException if any files are open when this function is called.
   */
  fun checkNoOpenFiles() {
    val firstOpenFile = openFiles.firstOrNull() ?: return
    throw IllegalStateException(
      """
      |expected 0 open files, but found:
      |    ${openFiles.joinToString(separator = "\n    ") { it.canonicalPath.toString() }}
      """.trimMargin(),
      firstOpenFile.backtrace
    )
  }

  override fun canonicalize(path: Path): Path {
    val canonicalPath = workingDirectory / path

    if (canonicalPath !in elements) {
      throw IOException("no such file: $path")
    }

    return canonicalPath
  }

  override fun metadataOrNull(path: Path): FileMetadata? {
    val canonicalPath = workingDirectory / path
    var element = elements[canonicalPath]

    // If the path is a root, create it on demand.
    if (element == null && path.isRoot) {
      element = Directory(createdAt = clock.now())
      elements[path] = element
    }

    return element?.metadata
  }

  override fun list(dir: Path): List<Path> {
    val canonicalPath = workingDirectory / dir
    val element = requireDirectory(canonicalPath)

    element.access(now = clock.now())
    return elements.keys.filter { it.parent == canonicalPath }
  }

  override fun source(file: Path): Source {
    val canonicalPath = workingDirectory / file
    val element = elements[canonicalPath] ?: throw FileNotFoundException("no such file: $file")

    if (element !is File) {
      throw IOException("not a file: $file")
    }

    val openFile = OpenFile(canonicalPath, Exception("file opened for reading here"))
    openFiles += openFile
    element.access(now = clock.now())
    return FakeFileSource(openFile, Buffer().write(element.data))
  }

  override fun sink(file: Path): Sink {
    return newSink(file, append = false)
  }

  override fun appendingSink(file: Path): Sink {
    return newSink(file, append = true)
  }

  private fun newSink(file: Path, append: Boolean): Sink {
    val canonicalPath = workingDirectory / file
    val now = clock.now()

    val existing = elements[canonicalPath]
    if (existing is Directory) {
      throw IOException("destination is a directory: $file")
    }
    val parent = requireDirectory(canonicalPath.parent)
    parent.access(now, true)

    val openFile = OpenFile(canonicalPath, Exception("file opened for writing here"))
    openFiles += openFile
    val regularFile = File(createdAt = existing?.createdAt ?: now)
    val result = FakeFileSink(openFile, regularFile)
    if (append && existing is File) {
      result.buffer.write(existing.data)
      regularFile.data = existing.data
    }
    elements[canonicalPath] = regularFile
    regularFile.access(now = now, modified = true)
    return result
  }

  override fun createDirectory(dir: Path) {
    val canonicalPath = workingDirectory / dir

    if (elements[canonicalPath] != null) {
      throw IOException("already exists: $dir")
    }
    requireDirectory(canonicalPath.parent)

    elements[canonicalPath] = Directory(createdAt = clock.now())
  }

  override fun atomicMove(source: Path, target: Path) {
    val canonicalSource = workingDirectory / source
    val canonicalTarget = workingDirectory / target

    val targetElement = elements[canonicalTarget]
    val sourceElement = elements[canonicalSource]

    // Universal constraints.
    if (targetElement is Directory) {
      throw IOException("target is a directory: $target")
    }
    requireDirectory(canonicalTarget.parent)
    if (windowsLimitations) {
      // Windows-only constraints.
      openFileOrNull(canonicalSource)?.let {
        throw IOException("source is open $source", it.backtrace)
      }
      openFileOrNull(canonicalTarget)?.let {
        throw IOException("target is open $target", it.backtrace)
      }
    } else {
      // UNIX-only constraints.
      if (sourceElement is Directory && targetElement is File) {
        throw IOException("source is a directory and target is a file")
      }
    }

    val removed = elements.remove(canonicalSource)
      ?: throw IOException("source doesn't exist: $source")
    elements[canonicalTarget] = removed
  }

  override fun delete(path: Path) {
    val canonicalPath = workingDirectory / path

    if (elements.keys.any { it.parent == canonicalPath }) {
      throw IOException("non-empty directory")
    }

    if (windowsLimitations) {
      openFileOrNull(canonicalPath)?.let {
        throw IOException("file is open $path", it.backtrace)
      }
    }

    if (elements.remove(canonicalPath) == null) {
      throw IOException("no such file: $path")
    }
  }

  /**
   * Gets the directory at [path], creating it if [path] is a filesystem root.
   *
   * @throws IOException if the named directory is not a root and does not exist, or if it does
   *     exist but is not a directory.
   */
  private fun requireDirectory(path: Path?): Directory {
    if (path == null) throw IOException("directory does not exist")

    // If the path is a directory, return it!
    val element = elements[path]
    if (element is Directory) return element

    // If the path is a root, create it on demand.
    if (element == null && path.isRoot) {
      val root = Directory(createdAt = clock.now())
      elements[path] = root
      return root
    }

    throw IOException("path is not a directory: $path")
  }

  private sealed class Element(
    val createdAt: Instant
  ) {
    var lastModifiedAt: Instant = createdAt
    var lastAccessedAt: Instant = createdAt

    class File(createdAt: Instant) : Element(createdAt) {
      var data: ByteString = ByteString.EMPTY

      override val metadata: FileMetadata
        get() = FileMetadata(
          isRegularFile = true,
          size = data.size.toLong(),
          createdAtMillis = createdAt.toEpochMilliseconds(),
          lastModifiedAtMillis = lastModifiedAt.toEpochMilliseconds(),
          lastAccessedAtMillis = lastAccessedAt.toEpochMilliseconds(),
          isDirectory = false
        )
    }

    class Directory(createdAt: Instant) : Element(createdAt) {
      override val metadata: FileMetadata
        get() = FileMetadata(
          isDirectory = true,
          createdAtMillis = createdAt.toEpochMilliseconds(),
          lastModifiedAtMillis = lastModifiedAt.toEpochMilliseconds(),
          lastAccessedAtMillis = lastAccessedAt.toEpochMilliseconds(),
          isRegularFile = false,
          size = null
        )
    }

    fun access(now: Instant, modified: Boolean = false) {
      lastAccessedAt = now
      if (modified) {
        lastModifiedAt = now
      }
    }

    abstract val metadata: FileMetadata
  }

  private fun openFileOrNull(canonicalPath: Path): OpenFile? {
    return openFiles.firstOrNull { it.canonicalPath == canonicalPath }
  }

  private class OpenFile(
    val canonicalPath: Path,
    val backtrace: Throwable
  )

  /** Reads data from [buffer], removing itself from [openPathsMutable] when closed. */
  private inner class FakeFileSource(
    private val openFile: OpenFile,
    private val buffer: Buffer
  ) : Source {
    private var closed = false

    override fun read(sink: Buffer, byteCount: Long): Long {
      check(!closed) { "closed" }
      return buffer.read(sink, byteCount)
    }

    override fun timeout() = Timeout.NONE

    override fun close() {
      if (closed) return
      closed = true
      openFiles -= openFile
    }

    override fun toString() = "source(${openFile.canonicalPath})"
  }

  /** Writes data to [path]. */
  private inner class FakeFileSink(
    private val openFile: OpenFile,
    private val file: File
  ) : Sink {
    val buffer = Buffer()
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
      check(!closed) { "closed" }
      buffer.write(source, byteCount)
    }

    override fun flush() {
      check(!closed) { "closed" }
      file.data = buffer.snapshot()
      file.access(now = clock.now(), modified = true)
    }

    override fun timeout() = Timeout.NONE

    override fun close() {
      if (closed) return
      closed = true
      file.data = buffer.snapshot()
      file.access(now = clock.now(), modified = true)
      openFiles -= openFile
    }

    override fun toString() = "sink(${openFile.canonicalPath})"
  }

  override fun toString() = "FakeFilesystem"
}