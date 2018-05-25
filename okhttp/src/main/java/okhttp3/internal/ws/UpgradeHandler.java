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
package okhttp3.internal.ws;

import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * https://datatracker.ietf.org/doc/draft-ietf-httpbis-h2-websockets/?include_text=1
 */
public interface UpgradeHandler<T> {
  T connect(OkHttpClient client);

  boolean supportsHttp11();

  boolean supportsHttp2();

  /**
   * https://www.iana.org/assignments/http-upgrade-tokens/http-upgrade-tokens.xhtml
   */
  String connectProtocol();

  Request addUpgradeHeaders(Request upgradeRequest);

  void failConnect(Response response, IOException e) throws IOException;
}
