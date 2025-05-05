/*
 * Copyright (C) 2021 Square, Inc.
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
@file:Suppress("ktlint:standard:filename")

package okhttp3.internal

import okio.ArrayIndexOutOfBoundsException
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.Closeable
import okio.FileNotFoundException
import okio.FileSystem
import okio.IOException
import okio.Options
import okio.Path
import okio.use

@JvmField
internal val EMPTY_BYTE_ARRAY: ByteArray = ByteArray(0)

/** Byte order marks. */
internal val UNICODE_BOMS =
  Options.of(
    // UTF-8.
    "efbbbf".decodeHex(),
    // UTF-16BE.
    "feff".decodeHex(),
    // UTF-32LE.
    "fffe0000".decodeHex(),
    // UTF-16LE.
    "fffe".decodeHex(),
    // UTF-32BE.
    "0000feff".decodeHex(),
  )

/**
 * Returns an array containing only elements found in this array and also in [other]. The returned
 * elements are in the same order as in this.
 */
internal fun Array<String>.intersect(
  other: Array<String>,
  comparator: Comparator<in String>,
): Array<String> {
  val result = mutableListOf<String>()
  for (a in this) {
    for (b in other) {
      if (comparator.compare(a, b) == 0) {
        result.add(a)
        break
      }
    }
  }
  return result.toTypedArray()
}

/**
 * Returns true if there is an element in this array that is also in [other]. This method terminates
 * if any intersection is found. The sizes of both arguments are assumed to be so small, and the
 * likelihood of an intersection so great, that it is not worth the CPU cost of sorting or the
 * memory cost of hashing.
 */
internal fun Array<String>.hasIntersection(
  other: Array<String>?,
  comparator: Comparator<in String>,
): Boolean {
  if (isEmpty() || other == null || other.isEmpty()) {
    return false
  }
  for (a in this) {
    for (b in other) {
      if (comparator.compare(a, b) == 0) {
        return true
      }
    }
  }
  return false
}

internal fun Array<String>.indexOf(
  value: String,
  comparator: Comparator<String>,
): Int = indexOfFirst { comparator.compare(it, value) == 0 }

@Suppress("UNCHECKED_CAST")
internal fun Array<String>.concat(value: String): Array<String> {
  val result = copyOf(size + 1)
  result[result.lastIndex] = value
  return result as Array<String>
}

/** Increments [startIndex] until this string is not ASCII whitespace. Stops at [endIndex]. */
internal fun String.indexOfFirstNonAsciiWhitespace(
  startIndex: Int = 0,
  endIndex: Int = length,
): Int {
  for (i in startIndex until endIndex) {
    when (this[i]) {
      '\t', '\n', '\u000C', '\r', ' ' -> Unit
      else -> return i
    }
  }
  return endIndex
}

/**
 * Decrements [endIndex] until `input[endIndex - 1]` is not ASCII whitespace. Stops at [startIndex].
 */
internal fun String.indexOfLastNonAsciiWhitespace(
  startIndex: Int = 0,
  endIndex: Int = length,
): Int {
  for (i in endIndex - 1 downTo startIndex) {
    when (this[i]) {
      '\t', '\n', '\u000C', '\r', ' ' -> Unit
      else -> return i + 1
    }
  }
  return startIndex
}

/** Equivalent to `string.substring(startIndex, endIndex).trim()`. */
fun String.trimSubstring(
  startIndex: Int = 0,
  endIndex: Int = length,
): String {
  val start = indexOfFirstNonAsciiWhitespace(startIndex, endIndex)
  val end = indexOfLastNonAsciiWhitespace(start, endIndex)
  return substring(start, end)
}

/**
 * Returns the index of the first character in this string that contains a character in
 * [delimiters]. Returns endIndex if there is no such character.
 */
fun String.delimiterOffset(
  delimiters: String,
  startIndex: Int = 0,
  endIndex: Int = length,
): Int {
  for (i in startIndex until endIndex) {
    if (this[i] in delimiters) return i
  }
  return endIndex
}

/**
 * Returns the index of the first character in this string that is [delimiter]. Returns [endIndex]
 * if there is no such character.
 */
fun String.delimiterOffset(
  delimiter: Char,
  startIndex: Int = 0,
  endIndex: Int = length,
): Int {
  for (i in startIndex until endIndex) {
    if (this[i] == delimiter) return i
  }
  return endIndex
}

/**
 * Returns the index of the first character in this string that is either a control character (like
 * `\u0000` or `\n`) or a non-ASCII character. Returns -1 if this string has no such characters.
 */
internal fun String.indexOfControlOrNonAscii(): Int {
  for (i in 0 until length) {
    val c = this[i]
    if (c <= '\u001f' || c >= '\u007f') {
      return i
    }
  }
  return -1
}

/** Returns true if we should void putting this this header in an exception or toString(). */
internal fun isSensitiveHeader(name: String): Boolean =
  name.equals("Authorization", ignoreCase = true) ||
    name.equals("Cookie", ignoreCase = true) ||
    name.equals("Proxy-Authorization", ignoreCase = true) ||
    name.equals("Set-Cookie", ignoreCase = true)

internal fun Char.parseHexDigit(): Int =
  when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    in 'A'..'F' -> this - 'A' + 10
    else -> -1
  }

internal infix fun Byte.and(mask: Int): Int = toInt() and mask

internal infix fun Short.and(mask: Int): Int = toInt() and mask

internal infix fun Int.and(mask: Long): Long = toLong() and mask

@Throws(IOException::class)
internal fun BufferedSink.writeMedium(medium: Int) {
  writeByte(medium.ushr(16) and 0xff)
  writeByte(medium.ushr(8) and 0xff)
  writeByte(medium and 0xff)
}

@Throws(IOException::class)
internal fun BufferedSource.readMedium(): Int =
  (
    readByte() and 0xff shl 16
      or (readByte() and 0xff shl 8)
      or (readByte() and 0xff)
  )

/** Run [block] until it either throws an [IOException] or completes. */
internal inline fun ignoreIoExceptions(block: () -> Unit) {
  try {
    block()
  } catch (_: IOException) {
  }
}

internal fun Buffer.skipAll(b: Byte): Int {
  var count = 0
  while (!exhausted() && this[0] == b) {
    count++
    readByte()
  }
  return count
}

/**
 * Returns the index of the next non-whitespace character in this. Result is undefined if input
 * contains newline characters.
 */
internal fun String.indexOfNonWhitespace(startIndex: Int = 0): Int {
  for (i in startIndex until length) {
    val c = this[i]
    if (c != ' ' && c != '\t') {
      return i
    }
  }
  return length
}

fun String.toLongOrDefault(defaultValue: Long): Long =
  try {
    toLong()
  } catch (_: NumberFormatException) {
    defaultValue
  }

/**
 * Returns this as a non-negative integer, or 0 if it is negative, or [Int.MAX_VALUE] if it is too
 * large, or [defaultValue] if it cannot be parsed.
 */
internal fun String?.toNonNegativeInt(defaultValue: Int): Int {
  try {
    val value = this?.toLong() ?: return defaultValue
    return when {
      value > Int.MAX_VALUE -> Int.MAX_VALUE
      value < 0 -> 0
      else -> value.toInt()
    }
  } catch (_: NumberFormatException) {
    return defaultValue
  }
}

/** Closes this, ignoring any checked exceptions. */
fun Closeable.closeQuietly() {
  try {
    close()
  } catch (rethrown: RuntimeException) {
    throw rethrown
  } catch (_: Exception) {
  }
}

/**
 * Returns true if file streams can be manipulated independently of their paths. This is typically
 * true for systems like Mac, Unix, and Linux that use inodes in their file system interface. It is
 * typically false on Windows.
 *
 * If this returns false we won't permit simultaneous reads and writes. When writes commit we need
 * to delete the previous snapshots, and that won't succeed if the file is open. (We do permit
 * multiple simultaneous reads.)
 *
 * @param file a file in the directory to check. This file shouldn't already exist!
 */
internal fun FileSystem.isCivilized(file: Path): Boolean {
  sink(file).use {
    try {
      delete(file)
      return true
    } catch (_: IOException) {
    }
  }
  delete(file)
  return false
}

/** Delete file we expect but don't require to exist. */
internal fun FileSystem.deleteIfExists(path: Path) {
  try {
    delete(path)
  } catch (fnfe: FileNotFoundException) {
    return
  }
}

/** Tolerant delete, try to clear as many files as possible even after a failure. */
internal fun FileSystem.deleteContents(directory: Path) {
  var exception: IOException? = null
  val files =
    try {
      list(directory)
    } catch (fnfe: FileNotFoundException) {
      return
    }
  for (file in files) {
    try {
      if (metadata(file).isDirectory) {
        deleteContents(file)
      }

      delete(file)
    } catch (ioe: IOException) {
      if (exception == null) {
        exception = ioe
      }
    }
  }
  if (exception != null) {
    throw exception
  }
}

internal fun <E> MutableList<E>.addIfAbsent(element: E) {
  if (!contains(element)) add(element)
}

internal fun Exception.withSuppressed(suppressed: List<Exception>): Throwable =
  apply {
    for (e in suppressed) addSuppressed(e)
  }

internal inline fun <T> Iterable<T>.filterList(predicate: T.() -> Boolean): List<T> {
  var result: List<T> = emptyList()
  for (i in this) {
    if (predicate(i)) {
      if (result.isEmpty()) result = mutableListOf()
      (result as MutableList<T>).add(i)
    }
  }
  return result
}

internal const val USER_AGENT: String = "okhttp/${CONST_VERSION}"

internal fun checkOffsetAndCount(
  arrayLength: Long,
  offset: Long,
  count: Long,
) {
  if (offset or count < 0L || offset > arrayLength || arrayLength - offset < count) {
    throw ArrayIndexOutOfBoundsException("length=$arrayLength, offset=$offset, count=$offset")
  }
}

internal fun <T> interleave(
  a: Iterable<T>,
  b: Iterable<T>,
): List<T> {
  val ia = a.iterator()
  val ib = b.iterator()

  return buildList {
    while (ia.hasNext() || ib.hasNext()) {
      if (ia.hasNext()) {
        add(ia.next())
      }
      if (ib.hasNext()) {
        add(ib.next())
      }
    }
  }
}
