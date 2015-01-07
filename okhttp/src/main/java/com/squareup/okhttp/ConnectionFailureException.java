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

import java.io.IOException;
import java.util.LinkedList;

/**
 * An exception thrown to indicate a problem connecting to an address. Multiple attempts may have
 * been made, none of which were successful.
 */
public class ConnectionFailureException extends Exception {

  private final LinkedList<IOException> connectExceptions = new LinkedList<IOException>();

  public ConnectionFailureException(IOException cause) {
    super(cause);
    connectExceptions.add(cause);
  }

  public IOException getLastConnectException() {
    return connectExceptions.getLast();
  }

  public void addConnectException(IOException e) {
    connectExceptions.add(e);
  }
}
