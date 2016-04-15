/*
 * Copyright (C) 2016 Square, Inc.
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
import java.io.IOException;
import java.net.URL;

/**
 * Request filter based on the request's URL.
 *
 * @deprecated use {@link okhttp3.Interceptor} for non-HttpURLConnection filtering.
 */
public interface URLFilter {
  /**
   * Check whether request to the provided URL is permitted to be issued.
   *
   * @throws IOException if the request to the provided URL is not permitted.
   */
  void checkURLPermitted(URL url) throws IOException;
}
