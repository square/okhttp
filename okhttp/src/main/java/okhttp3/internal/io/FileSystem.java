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
package okhttp3.internal.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import okio.Okio;
import okio.Sink;
import okio.Source;

/**
 * Access to read and write files on a hierarchical data store. Most callers should use the {@link
 * #SYSTEM} implementation, which uses the host machine's local file system. Alternate
 * implementations may be used to inject faults (for testing) or to transform stored data (to add
 * encryption, for example).
 *
 * <p>All operations on a file system are racy. For example, guarding a call to {@link #source} with
 * {@link #exists} does not guarantee that {@link FileNotFoundException} will not be thrown. The
 * file may be moved between the two calls!
 *
 * <p>This interface is less ambitious than {@link java.nio.file.FileSystem} introduced in Java 7.
 * It lacks important features like file watching, metadata, permissions, and disk space
 * information. In exchange for these limitations, this interface is easier to implement and works
 * on all versions of Java and Android.
 */
public interface FileSystem {
  /** The host machine's local file system. */
  FileSystem SYSTEM = new FileSystem() {
    @Override public Source source(File file) throws FileNotFoundException {
      return Okio.source(file);
    }

    @Override public Sink sink(File file) throws FileNotFoundException {
      try {
        return Okio.sink(file);
      } catch (FileNotFoundException e) {
        // Maybe the parent directory doesn't exist? Try creating it first.
        file.getParentFile().mkdirs();
        return Okio.sink(file);
      }
    }

    @Override public Sink appendingSink(File file) throws FileNotFoundException {
      try {
        return Okio.appendingSink(file);
      } catch (FileNotFoundException e) {
        // Maybe the parent directory doesn't exist? Try creating it first.
        file.getParentFile().mkdirs();
        return Okio.appendingSink(file);
      }
    }

    @Override public void delete(File file) throws IOException {
      // If delete() fails, make sure it's because the file didn't exist!
      if (!file.delete() && file.exists()) {
        throw new IOException("failed to delete " + file);
      }
    }

    @Override public boolean exists(File file) {
      return file.exists();
    }

    @Override public long size(File file) {
      return file.length();
    }

    @Override public void rename(File from, File to) throws IOException {
      delete(to);
      if (!from.renameTo(to)) {
        throw new IOException("failed to rename " + from + " to " + to);
      }
    }

    @Override public void deleteContents(File directory) throws IOException {
      File[] files = directory.listFiles();
      if (files == null) {
        throw new IOException("not a readable directory: " + directory);
      }
      for (File file : files) {
        if (file.isDirectory()) {
          deleteContents(file);
        }
        if (!file.delete()) {
          throw new IOException("failed to delete " + file);
        }
      }
    }
  };

  /** Reads from {@code file}. */
  Source source(File file) throws FileNotFoundException;

  /**
   * Writes to {@code file}, discarding any data already present. Creates parent directories if
   * necessary.
   */
  Sink sink(File file) throws FileNotFoundException;

  /**
   * Writes to {@code file}, appending if data is already present. Creates parent directories if
   * necessary.
   */
  Sink appendingSink(File file) throws FileNotFoundException;

  /** Deletes {@code file} if it exists. Throws if the file exists and cannot be deleted. */
  void delete(File file) throws IOException;

  /** Returns true if {@code file} exists on the file system. */
  boolean exists(File file);

  /** Returns the number of bytes stored in {@code file}, or 0 if it does not exist. */
  long size(File file);

  /** Renames {@code from} to {@code to}. Throws if the file cannot be renamed. */
  void rename(File from, File to) throws IOException;

  /**
   * Recursively delete the contents of {@code directory}. Throws an IOException if any file could
   * not be deleted, or if {@code dir} is not a readable directory.
   */
  void deleteContents(File directory) throws IOException;
}
