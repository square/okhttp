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
package okhttp3

import okhttp3.ResponseBody.string
import okio.Buffer.writeUtf8
import okio.ForwardingSource.close
import okio.buffer
import okhttp3.ResponseBody.charStream
import okhttp3.ResponseBody.source
import okio.BufferedSource.exhausted
import okio.BufferedSource.readUtf8
import okio.BufferedSource.readByte
import okio.Source.close
import okhttp3.ResponseBody.bytes
import okhttp3.ResponseBody.byteString
import okio.ByteString.size
import okhttp3.ResponseBody.byteStream
import okhttp3.ResponseBody.close
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBodyTest
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ForwardingSource
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.lang.AssertionError
import java.lang.StringBuilder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class ResponseBodyTest {
    @Test
    @Throws(IOException::class)
    fun stringEmpty() {
        val body = body("")
        Assertions.assertThat(body.string()).isEqualTo("")
    }

    @Test
    @Throws(IOException::class)
    fun stringLooksLikeBomButTooShort() {
        val body = body("000048")
        Assertions.assertThat(body.string()).isEqualTo("\u0000\u0000H")
    }

    @Test
    @Throws(IOException::class)
    fun stringDefaultsToUtf8() {
        val body = body("68656c6c6f")
        Assertions.assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun stringExplicitCharset() {
        val body = body("00000068000000650000006c0000006c0000006f", "utf-32be")
        Assertions.assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun stringBomOverridesExplicitCharset() {
        val body = body("0000ffff00000068000000650000006c0000006c0000006f", "utf-8")
        Assertions.assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun stringBomUtf8() {
        val body = body("efbbbf68656c6c6f")
        Assertions.assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun stringBomUtf16Be() {
        val body = body("feff00680065006c006c006f")
        Assertions.assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun stringBomUtf16Le() {
        val body = body("fffe680065006c006c006f00")
        Assertions.assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun stringBomUtf32Be() {
        val body = body("0000ffff00000068000000650000006c0000006c0000006f")
        Assertions.assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun stringBomUtf32Le() {
        val body = body("ffff000068000000650000006c0000006c0000006f000000")
        Assertions.assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun stringClosesUnderlyingSource() {
        val closed = AtomicBoolean()
        val body: ResponseBody = object : ResponseBody() {
            override fun contentType(): MediaType? {
                return null
            }

            override fun contentLength(): Long {
                return 5
            }

            override fun source(): BufferedSource {
                val source = Buffer().writeUtf8("hello")
                return object : ForwardingSource(source) {
                    @Throws(IOException::class)
                    override fun close() {
                        closed.set(true)
                        super.close()
                    }
                }.buffer()
            }
        }
        Assertions.assertThat(body.string()).isEqualTo("hello")
        Assertions.assertThat(closed.get()).isTrue
    }

    @Test
    @Throws(IOException::class)
    fun readerEmpty() {
        val body = body("")
        Assertions.assertThat(exhaust(body.charStream())).isEqualTo("")
    }

    @Test
    @Throws(IOException::class)
    fun readerLooksLikeBomButTooShort() {
        val body = body("000048")
        Assertions.assertThat(exhaust(body.charStream())).isEqualTo("\u0000\u0000H")
    }

    @Test
    @Throws(IOException::class)
    fun readerDefaultsToUtf8() {
        val body = body("68656c6c6f")
        Assertions.assertThat(exhaust(body.charStream())).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun readerExplicitCharset() {
        val body = body("00000068000000650000006c0000006c0000006f", "utf-32be")
        Assertions.assertThat(exhaust(body.charStream())).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun readerBomUtf8() {
        val body = body("efbbbf68656c6c6f")
        Assertions.assertThat(exhaust(body.charStream())).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun readerBomUtf16Be() {
        val body = body("feff00680065006c006c006f")
        Assertions.assertThat(exhaust(body.charStream())).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun readerBomUtf16Le() {
        val body = body("fffe680065006c006c006f00")
        Assertions.assertThat(exhaust(body.charStream())).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun readerBomUtf32Be() {
        val body = body("0000ffff00000068000000650000006c0000006c0000006f")
        Assertions.assertThat(exhaust(body.charStream())).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun readerBomUtf32Le() {
        val body = body("ffff000068000000650000006c0000006c0000006f000000")
        Assertions.assertThat(exhaust(body.charStream())).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun readerClosedBeforeBomClosesUnderlyingSource() {
        val closed = AtomicBoolean()
        val body: ResponseBody = object : ResponseBody() {
            override fun contentType(): MediaType? {
                return null
            }

            override fun contentLength(): Long {
                return 5
            }

            override fun source(): BufferedSource {
                val body = body("fffe680065006c006c006f00")
                return object : ForwardingSource(body.source()) {
                    @Throws(IOException::class)
                    override fun close() {
                        closed.set(true)
                        super.close()
                    }
                }.buffer()
            }
        }
        body.charStream().close()
        Assertions.assertThat(closed.get()).isTrue
    }

    @Test
    @Throws(IOException::class)
    fun readerClosedAfterBomClosesUnderlyingSource() {
        val closed = AtomicBoolean()
        val body: ResponseBody = object : ResponseBody() {
            override fun contentType(): MediaType? {
                return null
            }

            override fun contentLength(): Long {
                return 5
            }

            override fun source(): BufferedSource {
                val body = body("fffe680065006c006c006f00")
                return object : ForwardingSource(body.source()) {
                    @Throws(IOException::class)
                    override fun close() {
                        closed.set(true)
                        super.close()
                    }
                }.buffer()
            }
        }
        val reader = body.charStream()
        Assertions.assertThat(reader.read()).isEqualTo('h'.code)
        reader.close()
        Assertions.assertThat(closed.get()).isTrue
    }

    @Test
    @Throws(IOException::class)
    fun sourceEmpty() {
        val body = body("")
        val source = body.source()
        Assertions.assertThat(source.exhausted()).isTrue
        Assertions.assertThat(source.readUtf8()).isEqualTo("")
    }

    @Test
    @Throws(IOException::class)
    fun sourceSeesBom() {
        val body = body("efbbbf68656c6c6f")
        val source = body.source()
        Assertions.assertThat(source.readByte() and 0xff).isEqualTo(0xef)
        Assertions.assertThat(source.readByte() and 0xff).isEqualTo(0xbb)
        Assertions.assertThat(source.readByte() and 0xff).isEqualTo(0xbf)
        Assertions.assertThat(source.readUtf8()).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun sourceClosesUnderlyingSource() {
        val closed = AtomicBoolean()
        val body: ResponseBody = object : ResponseBody() {
            override fun contentType(): MediaType? {
                return null
            }

            override fun contentLength(): Long {
                return 5
            }

            override fun source(): BufferedSource {
                val source = Buffer().writeUtf8("hello")
                return object : ForwardingSource(source) {
                    @Throws(IOException::class)
                    override fun close() {
                        closed.set(true)
                        super.close()
                    }
                }.buffer()
            }
        }
        body.source().close()
        Assertions.assertThat(closed.get()).isTrue
    }

    @Test
    @Throws(IOException::class)
    fun bytesEmpty() {
        val body = body("")
        Assertions.assertThat(body.bytes().size).isEqualTo(0)
    }

    @Test
    @Throws(IOException::class)
    fun bytesSeesBom() {
        val body = body("efbbbf68656c6c6f")
        val bytes = body.bytes()
        Assertions.assertThat(bytes[0] and 0xff).isEqualTo(0xef)
        Assertions.assertThat(bytes[1] and 0xff).isEqualTo(0xbb)
        Assertions.assertThat(bytes[2] and 0xff).isEqualTo(0xbf)
        Assertions.assertThat(String(bytes, 3, 5, StandardCharsets.UTF_8)).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun bytesClosesUnderlyingSource() {
        val closed = AtomicBoolean()
        val body: ResponseBody = object : ResponseBody() {
            override fun contentType(): MediaType? {
                return null
            }

            override fun contentLength(): Long {
                return 5
            }

            override fun source(): BufferedSource {
                val source = Buffer().writeUtf8("hello")
                return object : ForwardingSource(source) {
                    @Throws(IOException::class)
                    override fun close() {
                        closed.set(true)
                        super.close()
                    }
                }.buffer()
            }
        }
        Assertions.assertThat(body.bytes().size).isEqualTo(5)
        Assertions.assertThat(closed.get()).isTrue
    }

    @Test
    fun bytesThrowsWhenLengthsDisagree() {
        val body: ResponseBody = object : ResponseBody() {
            override fun contentType(): MediaType? {
                return null
            }

            override fun contentLength(): Long {
                return 10
            }

            override fun source(): BufferedSource {
                return Buffer().writeUtf8("hello")
            }
        }
        try {
            body.bytes()
            org.junit.jupiter.api.Assertions.fail<Any>()
        } catch (e: IOException) {
            Assertions.assertThat(e.message).isEqualTo(
                "Content-Length (10) and stream length (5) disagree"
            )
        }
    }

    @Test
    fun bytesThrowsMoreThanIntMaxValue() {
        val body: ResponseBody = object : ResponseBody() {
            override fun contentType(): MediaType? {
                return null
            }

            override fun contentLength(): Long {
                return Int.MAX_VALUE + 1L
            }

            override fun source(): BufferedSource {
                throw AssertionError()
            }
        }
        try {
            body.bytes()
            org.junit.jupiter.api.Assertions.fail<Any>()
        } catch (e: IOException) {
            Assertions.assertThat(e.message).isEqualTo(
                "Cannot buffer entire body for content length: 2147483648"
            )
        }
    }

    @Test
    @Throws(IOException::class)
    fun byteStringEmpty() {
        val body = body("")
        Assertions.assertThat(body.byteString()).isEqualTo(EMPTY)
    }

    @Test
    @Throws(IOException::class)
    fun byteStringSeesBom() {
        val body = body("efbbbf68656c6c6f")
        val actual = body.byteString()
        val expected: ByteString = decodeHex.decodeHex("efbbbf68656c6c6f")
        Assertions.assertThat(actual).isEqualTo(expected)
    }

    @Test
    @Throws(IOException::class)
    fun byteStringClosesUnderlyingSource() {
        val closed = AtomicBoolean()
        val body: ResponseBody = object : ResponseBody() {
            override fun contentType(): MediaType? {
                return null
            }

            override fun contentLength(): Long {
                return 5
            }

            override fun source(): BufferedSource {
                val source = Buffer().writeUtf8("hello")
                return object : ForwardingSource(source) {
                    @Throws(IOException::class)
                    override fun close() {
                        closed.set(true)
                        super.close()
                    }
                }.buffer()
            }
        }
        Assertions.assertThat(body.byteString().size).isEqualTo(5)
        Assertions.assertThat(closed.get()).isTrue
    }

    @Test
    fun byteStringThrowsWhenLengthsDisagree() {
        val body: ResponseBody = object : ResponseBody() {
            override fun contentType(): MediaType? {
                return null
            }

            override fun contentLength(): Long {
                return 10
            }

            override fun source(): BufferedSource {
                return Buffer().writeUtf8("hello")
            }
        }
        try {
            body.byteString()
            org.junit.jupiter.api.Assertions.fail<Any>()
        } catch (e: IOException) {
            Assertions.assertThat(e.message).isEqualTo(
                "Content-Length (10) and stream length (5) disagree"
            )
        }
    }

    @Test
    fun byteStringThrowsMoreThanIntMaxValue() {
        val body: ResponseBody = object : ResponseBody() {
            override fun contentType(): MediaType? {
                return null
            }

            override fun contentLength(): Long {
                return Int.MAX_VALUE + 1L
            }

            override fun source(): BufferedSource {
                throw AssertionError()
            }
        }
        try {
            body.byteString()
            org.junit.jupiter.api.Assertions.fail<Any>()
        } catch (e: IOException) {
            Assertions.assertThat(e.message).isEqualTo(
                "Cannot buffer entire body for content length: 2147483648"
            )
        }
    }

    @Test
    @Throws(IOException::class)
    fun byteStreamEmpty() {
        val body = body("")
        val bytes = body.byteStream()
        Assertions.assertThat(bytes.read()).isEqualTo(-1)
    }

    @Test
    @Throws(IOException::class)
    fun byteStreamSeesBom() {
        val body = body("efbbbf68656c6c6f")
        val bytes = body.byteStream()
        Assertions.assertThat(bytes.read()).isEqualTo(0xef)
        Assertions.assertThat(bytes.read()).isEqualTo(0xbb)
        Assertions.assertThat(bytes.read()).isEqualTo(0xbf)
        Assertions.assertThat(exhaust(InputStreamReader(bytes, StandardCharsets.UTF_8))).isEqualTo("hello")
    }

    @Test
    @Throws(IOException::class)
    fun byteStreamClosesUnderlyingSource() {
        val closed = AtomicBoolean()
        val body: ResponseBody = object : ResponseBody() {
            override fun contentType(): MediaType? {
                return null
            }

            override fun contentLength(): Long {
                return 5
            }

            override fun source(): BufferedSource {
                val source = Buffer().writeUtf8("hello")
                return object : ForwardingSource(source) {
                    @Throws(IOException::class)
                    override fun close() {
                        closed.set(true)
                        super.close()
                    }
                }.buffer()
            }
        }
        body.byteStream().close()
        Assertions.assertThat(closed.get()).isTrue
    }

    @Test
    @Throws(IOException::class)
    fun throwingUnderlyingSourceClosesQuietly() {
        val body: ResponseBody = object : ResponseBody() {
            override fun contentType(): MediaType? {
                return null
            }

            override fun contentLength(): Long {
                return 5
            }

            override fun source(): BufferedSource {
                val source = Buffer().writeUtf8("hello")
                return object : ForwardingSource(source) {
                    @Throws(IOException::class)
                    override fun close() {
                        throw IOException("Broken!")
                    }
                }.buffer()
            }
        }
        Assertions.assertThat(body.source().readUtf8()).isEqualTo("hello")
        body.close()
    }

    companion object {
        @JvmOverloads
        fun body(hex: String?, charset: String? = null): ResponseBody {
            val mediaType = if (charset == null) null else "any/thing; charset=$charset".toMediaType()
            return ResponseBody.Companion.toResponseBody(mediaType)
        }

        @Throws(IOException::class)
        fun exhaust(reader: Reader): String {
            val builder = StringBuilder()
            val buf = CharArray(10)
            var read: Int
            while (reader.read(buf).also { read = it } != -1) {
                builder.append(buf, 0, read)
            }
            return builder.toString()
        }
    }
}
