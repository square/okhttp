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
package com.squareup.okhttp.internal.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import okio.Buffer;
import okio.Sink;
import okio.Source;

/** A simple file system where all files are held in memory. Not safe for concurrent use. */
public final class InMemoryFileSystem implements FileSystem {
  private final Map<File, Buffer> files = new LinkedHashMap<>();

  @Override public Source source(File file) throws FileNotFoundException {
    Buffer result = files.get(file);
    if (result == null) throw new FileNotFoundException();
    return result.clone();
  }

  @Override public Sink sink(File file) throws FileNotFoundException {
    Buffer result = new Buffer();
    files.put(file, result);
    return result;
  }

  @Override public Sink appendingSink(File file) throws FileNotFoundException {
    Buffer result = files.get(file);
    return result != null ? result : sink(file);
  }

  @Override public void delete(File file) throws IOException {
    files.remove(file);
  }

  @Override public boolean exists(File file) throws IOException {
    return files.containsKey(file);
  }

  @Override public long size(File file) {
    Buffer buffer = files.get(file);
    return buffer != null ? buffer.size() : 0L;
  }

  @Override public void rename(File from, File to) throws IOException {
    Buffer buffer = files.remove(from);
    if (buffer == null) throw new FileNotFoundException();
    files.put(to, buffer);
  }

  @Override public void deleteContents(File directory) throws IOException {
    String prefix = directory.toString() + "/";
    for (Iterator<File> i = files.keySet().iterator(); i.hasNext(); ) {
      File file = i.next();
      if (file.toString().startsWith(prefix)) i.remove();
    }
  }
}
