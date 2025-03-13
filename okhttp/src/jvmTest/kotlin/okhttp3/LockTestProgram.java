/*
 * Copyright (C) 2024 Block, Inc.
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
package okhttp3;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/**
 * Used in CacheLockTest via Java command.
 */
public class LockTestProgram {
  public static void main(String[] args) throws IOException {
    File lockFile = new File(args[0]);

    System.out.println("Locking " + lockFile);

    FileChannel channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.APPEND);

    channel.lock();

    System.out.println("Locked " + lockFile);

    System.in.read();
  }
}
