/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.okio.zipfilesystem

import okio.ByteString
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * An entry within a zip file.
 *
 * An entry has attributes such as its name (which is actually a path) and the uncompressed size
 * of the corresponding data. An entry does not contain the data itself, but can be used as a key
 * with [ZipFileSystem.source].
 */
internal class ZipEntry(
  /**
   * The name is actually a path and may contain `/` characters.
   *
   * *Security note:* Entry names can represent relative paths. `foo/../bar` or
   * `../bar/baz`, for example. If the entry name is being used to construct a filename
   * or as a path component, it must be validated or sanitized to ensure that files are not
   * written outside of the intended destination directory.
   *
   * At most 0xffff bytes when encoded.
   */
  val name: String,

  /**
   * Returns the comment for this `ZipEntry`, or `null` if there is no comment.
   * If we're reading a zip file using `ZipInputStream`, the comment is not available.
   */
  val comment: String,

  /**
   * Gets the checksum for this `ZipEntry`.
   *
   * Needs to be a long to distinguish -1 ("not set") from the 0xffffffff CRC32.
   *
   * @return the checksum, or -1 if the checksum has not been set.
   *
   * @throws IllegalArgumentException if `value` is < 0 or > 0xFFFFFFFFL.
   */
  val crc: Long,

  /**
   * Gets the compressed size of this `ZipEntry`.
   *
   * @return the compressed size, or -1 if the compressed size has not been
   * set.
   */
  val compressedSize: Long,

  /**
   * Gets the uncompressed size of this `ZipEntry`.
   *
   * @return the uncompressed size, or `-1` if the size has not been
   * set.
   */
  val size: Long,

  /**
   * Gets the compression method for this `ZipEntry`.
   *
   * @return the compression method, either `DEFLATED`, `STORED`
   * or -1 if the compression method has not been set.
   */
  val compressionMethod: Int,

  val time: Int,

  val modDate: Int,

  /**
   * Gets the extra information for this `ZipEntry`.
   *
   * @return a byte array containing the extra information, or `null` if
   * there is none.
   */
  val extra: ByteString,

  val localHeaderRelOffset: Long
) {
  /**
   * Gets the last modification time of this `ZipEntry`.
   *
   * @return the last modification time as the number of milliseconds since
   * Jan. 1, 1970.
   */
  fun getTime(): Long {
    if (time != -1) {
      val cal = GregorianCalendar()
      cal.set(Calendar.MILLISECOND, 0)
      cal.set(
        1980 + (modDate shr 9 and 0x7f),
        (modDate shr 5 and 0xf) - 1,
        modDate and 0x1f,
        time shr 11 and 0x1f,
        time shr 5 and 0x3f,
        time and 0x1f shl 1
      )
      return cal.time.time
    }
    return -1
  }

  /**
   * Determine whether or not this `ZipEntry` is a directory.
   *
   * @return `true` when this `ZipEntry` is a directory, `false` otherwise.
   */
  val isDirectory: Boolean
    get() = name[name.length - 1] == '/'

  companion object {
    /** Zip entry state: Deflated. */
    const val DEFLATED = 8

    /** Zip entry state: Stored. */
    const val STORED = 0
  }
}
