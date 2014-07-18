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
package com.squareup.okhttp.internal.spdy.hpackjson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Utilities for reading HPACK tests.
 */
public final class HpackJsonUtil {

  private static final String STORY_RESOURCE_FORMAT =
      "/hpack-test-case/%s/story_%02d.json";

  private static final Gson GSON = new GsonBuilder().create();

  private static Story readStory(InputStream jsonResource) throws Exception {
    return GSON.fromJson(new InputStreamReader(jsonResource, "UTF-8"), Story.class);
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
    return result;
  }

  private HpackJsonUtil() { } // Utilities only.
}