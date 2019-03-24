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

import java.util.ArrayList;
import java.util.List;

/**
 * Representation of one story, a set of request headers to encode or decode. This class is used
 * reflectively with Moshi to parse stories from files.
 */
public class Story implements Cloneable {
  public final static Story MISSING = new Story();

  static {
    MISSING.setFileName("missing");
  }

  private transient String fileName;
  private List<Case> cases;
  private int draft;
  private String description;

  /**
   * The filename is only used in the toString representation.
   */
  void setFileName(String fileName) {
    this.fileName = fileName;
  }

  public List<Case> getCases() {
    return cases;
  }

  /** We only expect stories that match the draft we've implemented to pass. */
  public int getDraft() {
    return draft;
  }

  @Override
  public Story clone() throws CloneNotSupportedException {
    Story story = new Story();
    story.fileName = this.fileName;
    story.cases = new ArrayList<>();
    for (Case caze : cases) {
      story.cases.add(caze.clone());
    }
    story.draft = draft;
    story.description = description;
    return story;
  }

  @Override
  public String toString() {
    // Used as the test name.
    return fileName;
  }
}
