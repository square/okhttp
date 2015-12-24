/*
 * Copyright (C) 2011 The Android Open Source Project
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
package okhttp3.internal;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import okhttp3.internal.io.FileSystem;
import okio.Buffer;
import okio.ForwardingSink;
import okio.Sink;
import okio.Source;

public final class FaultyFileSystem implements FileSystem {
  private final FileSystem delegate;
  private final Set<File> writeFaults = new LinkedHashSet<>();

  public FaultyFileSystem(FileSystem delegate) {
    this.delegate = delegate;
  }

  public void setFaulty(File file, boolean faulty) {
    if (faulty) {
      writeFaults.add(file);
    } else {
      writeFaults.remove(file);
    }
  }

  @Override public Source source(File file) throws FileNotFoundException {
    return delegate.source(file);
  }

  @Override public Sink sink(File file) throws FileNotFoundException {
    return new FaultySink(delegate.sink(file), file);
  }

  @Override public Sink appendingSink(File file) throws FileNotFoundException {
    return new FaultySink(delegate.appendingSink(file), file);
  }

  @Override public void delete(File file) throws IOException {
    delegate.delete(file);
  }

  @Override public boolean exists(File file) throws IOException {
    return delegate.exists(file);
  }

  @Override public long size(File file) {
    return delegate.size(file);
  }

  @Override public void rename(File from, File to) throws IOException {
    delegate.rename(from, to);
  }

  @Override public void deleteContents(File directory) throws IOException {
    delegate.deleteContents(directory);
  }

  private class FaultySink extends ForwardingSink {
    private final File file;

    public FaultySink(Sink delegate, File file) {
      super(delegate);
      this.file = file;
    }

    @Override public void write(Buffer source, long byteCount) throws IOException {
      if (writeFaults.contains(file)) throw new IOException("boom!");
      super.write(source, byteCount);
    }
  }
}
