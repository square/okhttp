/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.internal

import okio.BufferedSink
import okio.BufferedSource
import java.io.IOException

infix fun Byte.and(mask: Int): Int = toInt() and mask
infix fun Short.and(mask: Int): Int = toInt() and mask
infix fun Int.and(mask: Long): Long = toLong() and mask

@Throws(IOException::class)
fun BufferedSink.writeMedium(medium: Int) {
  writeByte(medium.ushr(16) and 0xff)
  writeByte(medium.ushr(8) and 0xff)
  writeByte(medium and 0xff)
}

@Throws(IOException::class)
fun BufferedSource.readMedium(): Int {
  return (readByte() and 0xff shl 16
      or (readByte() and 0xff shl 8)
      or (readByte() and 0xff))
}
