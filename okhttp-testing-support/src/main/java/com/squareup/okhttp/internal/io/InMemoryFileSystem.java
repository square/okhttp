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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okio.Buffer;
import okio.ForwardingSink;
import okio.ForwardingSource;
import okio.Sink;
import okio.Source;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/** A simple file system where all files are held in memory. Not safe for concurrent use. */
public final class InMemoryFileSystem implements FileSystem, TestRule {
  private final Map<File, Buffer> files = new LinkedHashMap<>();
  private final Map<Source, File> openSources = new IdentityHashMap<>();
  private final Map<Sink, File> openSinks = new IdentityHashMap<>();

  @Override public Statement apply(final Statement base, Description description) {
    return new Statement() {
      @Override public void evaluate() throws Throwable {
        base.evaluate();
        ensureResourcesClosed();
      }
    };
  }

  public void ensureResourcesClosed() {
    List<String> openResources = new ArrayList<>();
    for (File file : openSources.values()) {
      openResources.add("Source for " + file);
    }
    for (File file : openSinks.values()) {
      openResources.add("Sink for " + file);
    }
    if (!openResources.isEmpty()) {
      StringBuilder builder = new StringBuilder("Resources acquired but not closed:");
      for (String resource : openResources) {
        builder.append("\n * ").append(resource);
      }
      throw new IllegalStateException(builder.toString());
    }
  }

  @Override public Source source(File file) throws FileNotFoundException {
    Buffer result = files.get(file);
    if (result == null) throw new FileNotFoundException();

    final Source source = result.clone();
    openSources.put(source, file);

    return new ForwardingSource(source) {
      @Override public void close() throws IOException {
        openSources.remove(source);
        super.close();
      }
    };
  }

  @Override public Sink sink(File file) throws FileNotFoundException {
    return sink(file, false);
  }

  @Override public Sink appendingSink(File file) throws FileNotFoundException {
    return sink(file, true);
  }

  private Sink sink(File file, boolean appending) {
    Buffer result = null;
    if (appending) {
      result = files.get(file);
    }
    if (result == null) {
      result = new Buffer();
    }
    files.put(file, result);

    final Sink sink = result;
    openSinks.put(sink, file);

    return new ForwardingSink(sink) {
      @Override public void close() throws IOException {
        openSinks.remove(sink);
        super.close();
      }
    };
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
