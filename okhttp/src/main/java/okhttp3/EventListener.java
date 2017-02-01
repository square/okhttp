/*
 * Copyright (C) 2017 Square, Inc.
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

import java.net.InetAddress;
import java.util.List;

/**
 * Observes, modifies, and potentially short-circuits requests going out and the corresponding
 * responses coming back in. Typically interceptors add, remove, or transform headers on the request
 * or response.
 */
public class EventListener {
  public static final EventListener NULL_EVENT_LISTENER = new EventListener() {
  };

  void requestEnqueued(Call call) {
  }

  void dnsStart(Call call, String domainName) {
  }

  void dnsEnd(Call call, String domainName, List<InetAddress> inetAddressList,
      Throwable throwable) {
  }

  void connectStart(Call call, InetAddress address, int port) {
  }

  void secureConnectStart(Call call) {
  }

  void secureConnectEnd(Call call, String cipherSuite, String protocol, Throwable throwable) {
  }

  void connectEnd(Call call,  InetAddress address, int port, String protocol, Throwable throwable) {
  }

  void requestHeadersStart(Call call) {
  }

  void requestHeadersEnd(Call call, Throwable throwable) {
  }

  void requestBodyStart(Call call) {
  }

  void requestBodyEnd(Call call, Throwable throwable) {
  }

  void responseHeadersStart(Call call) {
  }

  void responseHeadersEnd(Call call, Throwable throwable) {
  }

  void responseBodyStart(Call call) {
  }

  void responseBodyEnd(Call call, Throwable throwable) {
  }
}
