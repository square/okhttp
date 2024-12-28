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
package okhttp3

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Path
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RequestBodyTest {
  private lateinit var filePath: okio.Path

  @BeforeEach
  fun setup(
    @TempDir tempDir: Path,
  ) {
    filePath = tempDir.toOkioPath() / "file.txt"
  }

  @Test
  fun testFileDescriptor() {
    assertOnFileDescriptor { fd ->
      val requestBody = fd.toRequestBody()

      assertThat(requestBody.contentLength()).isEqualTo(-1L)
      assertThat(requestBody.isOneShot()).isEqualTo(true)
    }
  }

  @Test
  fun testFileDescriptorRead() {
    assertOnFileDescriptor(content = "Hello") { fd ->
      val requestBody = fd.toRequestBody()

      val buffer = Buffer()
      requestBody.writeTo(buffer)
      assertThat(buffer.readUtf8()).isEqualTo("Hello")
    }
  }

  @Test
  fun testFileDescriptorDefaultMediaType() {
    assertOnFileDescriptor { fd ->
      val requestBody = fd.toRequestBody()

      assertThat(requestBody.contentType()).isNull()
    }
  }

  @Test
  fun testFileDescriptorMediaType() {
    assertOnFileDescriptor { fd ->
      val contentType = "text/plain".toMediaType()

      val requestBody = fd.toRequestBody(contentType)

      assertThat(requestBody.contentType()).isEqualTo(contentType)
    }
  }

  @Test
  fun testFileDescriptorReadTwice() {
    assertOnFileDescriptor(content = "Hello") { fd ->
      val requestBody = fd.toRequestBody()

      val buffer = Buffer()
      requestBody.writeTo(buffer)
      assertThat(buffer.readUtf8()).isEqualTo("Hello")

      assertThrows(IOException::class.java) {
        requestBody.writeTo(Buffer())
      }
    }
  }

  @Test
  fun testFileDescriptorAfterClose() {
    val closedRequestBody = assertOnFileDescriptor { it.toRequestBody() }

    assertThrows(IOException::class.java) {
      closedRequestBody.writeTo(Buffer())
    }
  }

  @Test
  fun testPathRead() {
    assertOnPath(content = "Hello") { path ->
      val requestBody = path.asRequestBody(FileSystem.SYSTEM)

      val buffer = Buffer()
      requestBody.writeTo(buffer)
      assertThat(buffer.readUtf8()).isEqualTo("Hello")
    }
  }

  private inline fun <T> assertOnFileDescriptor(
    content: String? = null,
    fn: (FileDescriptor) -> T,
  ): T {
    return assertOnPath(content) {
      FileInputStream(filePath.toFile()).use { fis ->
        fn(fis.fd)
      }
    }
  }

  private inline fun <T> assertOnPath(
    content: String? = null,
    fn: (okio.Path) -> T,
  ): T {
    FileSystem.SYSTEM.write(filePath) {
      if (content != null) {
        writeUtf8(content)
      }
    }

    return fn(filePath)
  }
}
