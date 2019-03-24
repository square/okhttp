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
package okhttp3.internal.http2.hpackjson;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import okio.Okio;

import static java.util.Arrays.asList;
import static okhttp3.internal.http2.hpackjson.Story.MISSING;

/**
 * Utilities for reading HPACK tests.
 */
public final class HpackJsonUtil {
  /** Earliest draft that is code-compatible with latest. */
  private static final int BASE_DRAFT = 9;

  private static final String STORY_RESOURCE_FORMAT = "/hpack-test-case/%s/story_%02d.json";

  private static final Moshi MOSHI = new Moshi.Builder().build();
  private static final JsonAdapter<Story> STORY_JSON_ADAPTER = MOSHI.adapter(Story.class);

  private static Story readStory(InputStream jsonResource) throws IOException {
    return STORY_JSON_ADAPTER.fromJson(Okio.buffer(Okio.source(jsonResource)));
  }

  private static Story readStory(File file) throws IOException {
    return STORY_JSON_ADAPTER.fromJson(Okio.buffer(Okio.source(file)));
  }

  /** Iterate through the hpack-test-case resources, only picking stories for the current draft. */
  public static String[] storiesForCurrentDraft() throws URISyntaxException {
    URL resource = HpackJsonUtil.class.getResource("/hpack-test-case");
    if (resource == null) {
      return new String[0];
    }
    File testCaseDirectory = new File(resource.toURI());
    List<String> storyNames = new ArrayList<>();
    for (File path : testCaseDirectory.listFiles()) {
      if (path.isDirectory() && asList(path.list()).contains("story_00.json")) {
        try {
          Story firstStory = readStory(new File(path, "story_00.json"));
          if (firstStory.getDraft() >= BASE_DRAFT) {
            storyNames.add(path.getName());
          }
        } catch (IOException ignored) {
          // Skip this path.
        }
      }
    }
    return storyNames.toArray(new String[storyNames.size()]);
  }

  /**
   * Reads stories named "story_xx.json" from the folder provided.
   */
  public static List<Story> readStories(String testFolderName) throws Exception {
    List<Story> result = new ArrayList<>();
    int i = 0;
    while (true) { // break after last test.
      String storyResourceName = String.format(STORY_RESOURCE_FORMAT, testFolderName, i);
      InputStream storyInputStream = HpackJsonUtil.class.getResourceAsStream(storyResourceName);
      if (storyInputStream == null) {
        break;
      }
      try {
        Story story = readStory(storyInputStream);
        story.setFileName(storyResourceName);
        result.add(story);
        i++;
      } finally {
        storyInputStream.close();
      }
    }

    if (result.isEmpty()) {
      // missing files
      return Collections.singletonList(MISSING);
    }

    return result;
  }

  private HpackJsonUtil() {
  } // Utilities only.
}
