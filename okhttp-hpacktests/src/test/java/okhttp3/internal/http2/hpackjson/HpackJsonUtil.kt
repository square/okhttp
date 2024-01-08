/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.internal.http2.hpackjson

import com.squareup.moshi.FromJson
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.IOException
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source

/**
 * Utilities for reading HPACK tests.
 */
object HpackJsonUtil {
  @Suppress("unused")
  private val MOSHI =
    Moshi.Builder()
      .add(
        object : Any() {
          @ToJson fun byteStringToJson(byteString: ByteString) = byteString.hex()

          @FromJson fun byteStringFromJson(json: String) = json.decodeHex()
        },
      )
      .add(KotlinJsonAdapterFactory())
      .build()
  private val STORY_JSON_ADAPTER = MOSHI.adapter(Story::class.java)

  private val fileSystem = FileSystem.SYSTEM

  private fun readStory(source: BufferedSource): Story {
    return STORY_JSON_ADAPTER.fromJson(source)!!
  }

  private fun readStory(file: Path): Story {
    fileSystem.read(file) {
      return readStory(this)
    }
  }

  /** Iterate through the hpack-test-case resources, only picking stories for the current draft.  */
  fun storiesForCurrentDraft(): Array<String> {
    val resource =
      HpackJsonUtil::class.java.getResource("/hpack-test-case")
        ?: return arrayOf()

    val testCaseDirectory = File(resource.toURI()).toOkioPath()
    val result = mutableListOf<String>()
    for (path in fileSystem.list(testCaseDirectory)) {
      val story00 = path / "story_00.json"
      if (!fileSystem.exists(story00)) continue
      try {
        readStory(story00)
        result.add(path.name)
      } catch (ignored: IOException) {
        // Skip this path.
      }
    }
    return result.toTypedArray<String>()
  }

  /**
   * Reads stories named "story_xx.json" from the folder provided.
   */
  fun readStories(testFolderName: String): List<Story> {
    val result = mutableListOf<Story>()
    var i = 0
    while (true) { // break after last test.
      val storyResourceName =
        String.format(
          "/hpack-test-case/%s/story_%02d.json",
          testFolderName,
          i,
        )
      val storyInputStream =
        HpackJsonUtil::class.java.getResourceAsStream(storyResourceName)
          ?: break
      try {
        storyInputStream.use {
          val story =
            readStory(storyInputStream.source().buffer())
              .copy(fileName = storyResourceName)
          result.add(story)
          i++
        }
      } finally {
        storyInputStream.close()
      }
    }

    if (result.isEmpty()) {
      // missing files
      result.add(Story.MISSING)
    }

    return result
  }
}
