/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.cache2

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.io.File
import java.io.RandomAccessFile
import java.util.Random
import kotlin.test.assertFailsWith
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import okio.sink
import okio.source
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileOperatorTest {
  @TempDir
  var tempDir: File? = null
  private var file: File? = null
  private var randomAccessFile: RandomAccessFile? = null

  @BeforeEach
  fun setUp() {
    file = File(tempDir, "test")
    randomAccessFile = RandomAccessFile(file, "rw")
  }

  @AfterEach
  fun tearDown() {
    randomAccessFile!!.close()
  }

  @Test
  fun read() {
    write("Hello, World".encodeUtf8())
    val operator =
      FileOperator(
        randomAccessFile!!.getChannel(),
      )
    val buffer = Buffer()
    operator.read(0, buffer, 5)
    assertThat(buffer.readUtf8()).isEqualTo("Hello")
    operator.read(4, buffer, 5)
    assertThat(buffer.readUtf8()).isEqualTo("o, Wo")
  }

  @Test
  fun write() {
    val operator =
      FileOperator(
        randomAccessFile!!.getChannel(),
      )
    val buffer1 = Buffer().writeUtf8("Hello, World")
    operator.write(0, buffer1, 5)
    assertThat(buffer1.readUtf8()).isEqualTo(", World")
    val buffer2 = Buffer().writeUtf8("icopter!")
    operator.write(3, buffer2, 7)
    assertThat(buffer2.readUtf8()).isEqualTo("!")
    assertThat<ByteString>(snapshot()).isEqualTo("Helicopter".encodeUtf8())
  }

  @Test
  fun readAndWrite() {
    val operator =
      FileOperator(
        randomAccessFile!!.getChannel(),
      )
    write("woman god creates dinosaurs destroys. ".encodeUtf8())
    val buffer = Buffer()
    operator.read(6, buffer, 21)
    operator.read(36, buffer, 1)
    operator.read(5, buffer, 5)
    operator.read(28, buffer, 8)
    operator.read(17, buffer, 10)
    operator.read(36, buffer, 2)
    operator.read(2, buffer, 4)
    operator.write(0, buffer, buffer.size)
    operator.read(0, buffer, 12)
    operator.read(47, buffer, 3)
    operator.read(45, buffer, 2)
    operator.read(47, buffer, 3)
    operator.read(26, buffer, 10)
    operator.read(23, buffer, 3)
    operator.write(47, buffer, buffer.size)
    operator.read(62, buffer, 6)
    operator.read(4, buffer, 19)
    operator.write(80, buffer, buffer.size)
    assertThat(snapshot()).isEqualTo(
      (
        "" +
          "god creates dinosaurs. " +
          "god destroys dinosaurs. " +
          "god creates man. " +
          "man destroys god. " +
          "man creates dinosaurs. "
      ).encodeUtf8(),
    )
  }

  @Test
  fun multipleOperatorsShareOneFile() {
    val operatorA =
      FileOperator(
        randomAccessFile!!.getChannel(),
      )
    val operatorB =
      FileOperator(
        randomAccessFile!!.getChannel(),
      )
    val bufferA = Buffer()
    val bufferB = Buffer()
    bufferA.writeUtf8("Dodgson!\n")
    operatorA.write(0, bufferA, 9)
    bufferB.writeUtf8("You shouldn't use my name.\n")
    operatorB.write(9, bufferB, 27)
    bufferA.writeUtf8("Dodgson, we've got Dodgson here!\n")
    operatorA.write(36, bufferA, 33)
    operatorB.read(0, bufferB, 9)
    assertThat(bufferB.readUtf8()).isEqualTo("Dodgson!\n")
    operatorA.read(9, bufferA, 27)
    assertThat(bufferA.readUtf8()).isEqualTo("You shouldn't use my name.\n")
    operatorB.read(36, bufferB, 33)
    assertThat(bufferB.readUtf8()).isEqualTo("Dodgson, we've got Dodgson here!\n")
  }

  @Test
  fun largeRead() {
    val data = randomByteString(1000000)
    write(data)
    val operator =
      FileOperator(
        randomAccessFile!!.getChannel(),
      )
    val buffer = Buffer()
    operator.read(0, buffer, data.size.toLong())
    assertThat(buffer.readByteString()).isEqualTo(data)
  }

  @Test
  fun largeWrite() {
    val data = randomByteString(1000000)
    val operator =
      FileOperator(
        randomAccessFile!!.getChannel(),
      )
    val buffer = Buffer().write(data)
    operator.write(0, buffer, data.size.toLong())
    assertThat(snapshot()).isEqualTo(data)
  }

  @Test
  fun readBounds() {
    val operator =
      FileOperator(
        randomAccessFile!!.getChannel(),
      )
    val buffer = Buffer()
    assertFailsWith<IndexOutOfBoundsException> {
      operator.read(0, buffer, -1L)
    }
  }

  @Test
  fun writeBounds() {
    val operator =
      FileOperator(
        randomAccessFile!!.getChannel(),
      )
    val buffer = Buffer().writeUtf8("abc")
    assertFailsWith<IndexOutOfBoundsException> {
      operator.write(0, buffer, -1L)
    }
    assertFailsWith<IndexOutOfBoundsException> {
      operator.write(0, buffer, 4L)
    }
  }

  private fun randomByteString(byteCount: Int): ByteString {
    val bytes = ByteArray(byteCount)
    Random(0).nextBytes(bytes)
    return ByteString.of(*bytes)
  }

  private fun snapshot(): ByteString {
    randomAccessFile!!.getChannel().force(false)
    val source = file!!.source().buffer()
    return source.readByteString()
  }

  private fun write(data: ByteString) {
    val sink = file!!.sink().buffer()
    sink.write(data)
    sink.close()
  }
}
