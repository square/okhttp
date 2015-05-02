/*
 * Copyright (C) 2015 Square, Inc.
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
package com.squareup.okhttp;

import com.google.gson.Gson;
import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

/**
 * The result of a test run from the <a href="https://github.com/w3c/web-platform-tests">W3C web
 * platform tests</a>. This class serves as a Gson model for browser test results.
 *
 * <p><strong>Note:</strong> When extracting the .json file from the browser after a test run, be
 * careful to avoid text encoding problems. In one environment, Safari was corrupting UTF-8 data
 * for download (but the clipboard was fine), and Firefox was corrupting UTF-8 data copied to the
 * clipboard (but the download was fine).
 */
public final class WebPlatformTestRun {
  List<TestResult> results;

  public SubtestResult get(String testName, String subtestName) {
    for (TestResult result : results) {
      if (testName.equals(result.test)) {
        for (SubtestResult subtestResult : result.subtests) {
          if (subtestName.equals(subtestResult.name)) {
            return subtestResult;
          }
        }
      }
    }
    return null;
  }

  public static WebPlatformTestRun load(InputStream in) throws IOException {
    try {
      return new Gson().getAdapter(WebPlatformTestRun.class)
          .fromJson(new InputStreamReader(in, Util.UTF_8));
    } finally {
      Util.closeQuietly(in);
    }
  }

  public static class TestResult {
    String test;
    List<SubtestResult> subtests;
  }

  public static class SubtestResult {
    String name;
    Status status;
    String message;

    public boolean isPass() {
      return status == Status.PASS;
    }
  }

  public enum Status {
    PASS, FAIL
  }
}
