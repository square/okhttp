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

/**
 * Selects routes to connect to an origin server.
 */
public interface RouteSelector {

  /**
   * Returns true if there's another route to attempt. Every address has at
   * least one route.
   */
  boolean hasNext();

  /**
   * Returns the next Route to attempt.
   *
   * @throws IOException if the next route could not be determined
   * @throws java.util.NoSuchElementException if there is no next route
   */
  Route next() throws IOException;

  /**
   * Clients should invoke this method when they encounter a connectivity
   * failure on a connection returned by this route selector.
   */
  void connectFailed(Route failedRoute, IOException failure);
}
